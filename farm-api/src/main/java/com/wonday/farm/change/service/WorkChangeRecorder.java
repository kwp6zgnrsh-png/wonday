package com.wonday.farm.change.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

import com.wonday.farm.change.entity.WorkChange;
import com.wonday.farm.change.exception.WorkEarlyEndException;
import com.wonday.farm.change.repository.WorkChangeRepository;
import com.wonday.farm.change.validation.EarlyEndInput;
import com.wonday.farm.insurance.entity.WorkChangeVersion;
import com.wonday.farm.insurance.exception.InsuranceApiException;
import com.wonday.farm.insurance.repository.ChangeStreamRepository;
import com.wonday.farm.insurance.repository.WorkChangeVersionRepository;
import com.wonday.farm.insurance.service.InsuranceTime;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import com.wonday.farm.work.entity.WorkAssignment;
import com.wonday.farm.work.repository.WorkAssignmentRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

// 변경 원본과 보험 조회용 불변 버전의 저장 경계다. 정식 조기 종료 API는 endWork에서 업무 검증까지 수행한다.
@Service
public class WorkChangeRecorder {
	private final MemberRepository members;
	private final WorkAssignmentRepository assignments;
	private final WorkChangeRepository changes;
	private final ChangeStreamRepository streams;
	private final WorkChangeVersionRepository versions;
	private final Clock clock;

	public WorkChangeRecorder(MemberRepository members, WorkAssignmentRepository assignments,
			WorkChangeRepository changes, ChangeStreamRepository streams, WorkChangeVersionRepository versions,
			@Qualifier("insuranceChangeClock") Clock clock) {
		this.members = members;
		this.assignments = assignments;
		this.changes = changes;
		this.streams = streams;
		this.versions = versions;
		this.clock = clock;
	}

	// 시작 기준은 약정 시작 시각이다. 후속 사용자 API가 취소/조기 종료의 시점 검증을 담당한다.
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public long recordValidatedChange(long actorId, long assignmentId, WorkChange.Type type,
			OffsetDateTime actualEndAt, WorkChange.Reason reason, WorkChange.WeatherReason weatherReason, String detail) {
		validateReason(reason, weatherReason, detail);
		if (type == null || (type == WorkChange.Type.EARLY_ENDED && actualEndAt == null)
				|| (type == WorkChange.Type.CANCELLED && actualEndAt != null)
				|| (actualEndAt != null && actualEndAt.getNano() % 1000 != 0)) {
			throw InsuranceApiException.invalidRequest();
		}
		var ownership = requireOwner(actorId, assignmentId);
		streams.ensureExists(ownership.getWorkerId());
		var stream = streams.lockWorker(ownership.getWorkerId()).orElseThrow(InsuranceApiException::unavailable);
		// 발행 순번 잠금을 유지하되, 근무 행 → 변경 행 순서를 완료 API와 맞춘다.
		var assignment = assignments.findByIdForUpdate(assignmentId).orElseThrow(InsuranceApiException::unavailable);
		requireNotCompleted(assignment);
		if (changes.findForUpdate(assignmentId).isPresent()) {
			throw new InsuranceApiException(HttpStatus.CONFLICT, "WORK_CHANGE_ALREADY_EXISTS", "이미 근무 변경이 기록되어 있습니다.");
		}
		InsuranceTime.zone(assignment.getAgreedTimeZone());
		LocalDateTime now = LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
		LocalDateTime effectiveAt = type == WorkChange.Type.CANCELLED ? now
			: LocalDateTime.ofInstant(actualEndAt.toInstant(), ZoneOffset.UTC);
		var change = changes.saveAndFlush(new WorkChange(assignmentId, actorId, type, reason, weatherReason, detail,
			effectiveAt, now));
		versions.save(new WorkChangeVersion(ownership.getWorkerId(), stream.nextSequence(), change, assignment.getAgreedTimeZone()));
		return change.getId();
	}

	// CHANGE-03: 같은 결과 확인과 신규 조기 종료의 시간 검사를 구분한다.
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public RecordedEarlyEnd endWork(long employerId, long assignmentId, OffsetDateTime actualEndAt,
			WorkChange.Reason reason, WorkChange.WeatherReason weatherReason, String detail) {
		if (employerId <= 0 || assignmentId <= 0) throw WorkEarlyEndException.invalid();
		var input = EarlyEndInput.values(actualEndAt, reason, weatherReason, detail);
		members.findById(employerId).filter(member -> member.memberRole().orElse(null) == MemberRole.EMPLOYER)
			.orElseThrow(() -> new AccessDeniedException("Employer role required"));
		var ownership = assignments.findOwnership(assignmentId).orElseThrow(WorkEarlyEndException::notFound);
		if (ownership.getEmployerId() != employerId) throw WorkEarlyEndException.notFound();

		// 보험 폴링·사유 보완과 같은 스트림 → 확정 근무 → 변경 행 순서를 유지한다.
		streams.ensureExists(ownership.getWorkerId());
		var stream = streams.lockWorker(ownership.getWorkerId()).orElseThrow(WorkEarlyEndException::unavailable);
		var assignment = assignments.findByIdForUpdate(assignmentId).orElseThrow(WorkEarlyEndException::notFound);
		if (assignment.getCompletedAtUtc() != null || assignment.getCompletedBy() != null) {
			throw WorkEarlyEndException.conflict("WORK_ALREADY_COMPLETED", "완료된 근무에는 조기 종료를 기록할 수 없습니다.");
		}
		var previous = changes.findForUpdate(assignmentId);
		if (previous.isPresent()) {
			var change = previous.get();
			if (change.getChangeType() == WorkChange.Type.CANCELLED) {
				throw WorkEarlyEndException.conflict("WORK_ALREADY_CANCELLED", "취소된 근무에는 조기 종료를 기록할 수 없습니다.");
			}
			if (change.getEffectiveAt() == null) throw WorkEarlyEndException.unavailable();
			// 오프셋 표기가 달라도 같은 시점이면 동일 요청이다. 설명은 정규화한 내용으로 비교한다.
			if (!change.getEffectiveAt().toInstant(ZoneOffset.UTC).equals(input.actualEndAt().toInstant())
					|| change.getReasonCode() != input.reason() || change.getWeatherReasonCode() != input.weather()
					|| !Objects.equals(EarlyEndInput.normalizeDetail(change.getReasonDetail()), input.detail())) {
				throw WorkEarlyEndException.conflict("EARLY_END_CONTENT_CONFLICT",
					"이미 기록된 조기 종료 내용과 다릅니다. 기존 기록은 변경하지 않습니다.");
			}
			// 입력 기한을 지난 동일 재요청에도 최초 ID·시각·버전을 유지한다.
			return new RecordedEarlyEnd(change, assignment.getAgreedTimeZone());
		}

		var zone = InsuranceTime.zone(assignment.getAgreedTimeZone());
		var start = InsuranceTime.local(assignment.getAgreedStartAt(), zone).toInstant();
		var end = InsuranceTime.local(assignment.getAgreedEndAt(), zone).toInstant();
		if (!end.isAfter(start)) throw WorkEarlyEndException.unavailable();
		// 잠금과 상태 검사가 끝난 실제 서버 시각이다. 비교 전에 정밀도를 낮추지 않는다.
		var instant = clock.instant();
		if (instant.isBefore(start)) {
			throw WorkEarlyEndException.conflict("WORK_NOT_STARTED", "약정 근무 시작 시각부터 조기 종료를 기록할 수 있습니다.");
		}
		if (instant.isAfter(end.plus(Duration.ofHours(24)))) throw WorkEarlyEndException.deadlinePassed();
		var effective = input.actualEndAt().toInstant();
		if (effective.isBefore(start) || !effective.isBefore(end) || effective.isAfter(instant)) {
			throw WorkEarlyEndException.invalidTime();
		}
		var now = LocalDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
		var change = changes.saveAndFlush(new WorkChange(assignmentId, employerId, WorkChange.Type.EARLY_ENDED,
			input.reason(), input.weather(), input.detail(), LocalDateTime.ofInstant(effective, ZoneOffset.UTC), now));
		versions.save(new WorkChangeVersion(ownership.getWorkerId(), stream.nextSequence(), change, assignment.getAgreedTimeZone()));
		return new RecordedEarlyEnd(change, assignment.getAgreedTimeZone());
	}

	public record RecordedEarlyEnd(WorkChange change, String timeZone) {}

	@Transactional(isolation = Isolation.READ_COMMITTED)
	public void reviseReason(long actorId, long assignmentId, WorkChange.Reason reason,
			WorkChange.WeatherReason weatherReason, String detail) {
		validateReason(reason, weatherReason, detail);
		var ownership = requireOwner(actorId, assignmentId);
		streams.ensureExists(ownership.getWorkerId());
		var stream = streams.lockWorker(ownership.getWorkerId()).orElseThrow(InsuranceApiException::unavailable);
		var assignment = assignments.findByIdForUpdate(assignmentId).orElseThrow(InsuranceApiException::unavailable);
		requireNotCompleted(assignment);
		var change = changes.findForUpdate(assignmentId).orElseThrow(() ->
			new InsuranceApiException(HttpStatus.NOT_FOUND, "WORK_CHANGE_NOT_FOUND", "근무 변경 기록을 찾을 수 없습니다."));
		InsuranceTime.zone(assignment.getAgreedTimeZone());
		change.revise(reason, weatherReason, detail,
			LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC));
		versions.save(new WorkChangeVersion(ownership.getWorkerId(), stream.nextSequence(), change, assignment.getAgreedTimeZone()));
	}

	private static void requireNotCompleted(WorkAssignment assignment) {
		if (assignment.getCompletedAtUtc() != null || assignment.getCompletedBy() != null) {
			throw new InsuranceApiException(HttpStatus.CONFLICT, "WORK_ALREADY_COMPLETED",
				"완료된 근무에는 취소·조기 종료를 기록하거나 정정할 수 없습니다.");
		}
	}

	private WorkAssignmentRepository.Ownership requireOwner(long actorId, long assignmentId) {
		members.findById(actorId).filter(member -> member.memberRole().orElse(null) == MemberRole.EMPLOYER)
			.orElseThrow(() -> new AccessDeniedException("Employer role required"));
		var ownership = assignments.findOwnership(assignmentId).orElseThrow(() ->
			new InsuranceApiException(HttpStatus.NOT_FOUND, "WORK_ASSIGNMENT_NOT_FOUND", "확정 근무를 찾을 수 없습니다."));
		if (ownership.getEmployerId() != actorId) throw new AccessDeniedException("Assignment owner required");
		return ownership;
	}

	private static void validateReason(WorkChange.Reason reason, WorkChange.WeatherReason weatherReason, String detail) {
		if (reason == null || (reason != WorkChange.Reason.WEATHER && weatherReason != null)
				|| (detail != null && detail.getBytes(StandardCharsets.UTF_8).length > 65535)) {
			throw InsuranceApiException.invalidRequest();
		}
	}
}
