package com.wonday.farm.change.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

import com.wonday.farm.change.entity.WorkChange;
import com.wonday.farm.change.exception.WorkCancellationException;
import com.wonday.farm.change.repository.WorkChangeRepository;
import com.wonday.farm.change.validation.CancellationInput;
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

// 변경 원본과 보험 조회용 불변 버전을 한 트랜잭션으로 기록한다. 사용자 취소는 시작 전 검증을 포함한 전용 메서드를 사용한다.
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

	// CHANGE-01·02의 최초 취소와 동일 내용 재요청을 처리한다.
	// 기존 demo 준비·조기 종료 저장 경로의 동작은 바꾸지 않고, 사용자 요청에만 시작 전 검사를 적용한다.
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public RecordedCancellation cancelBeforeStart(long employerId, long assignmentId, WorkChange.Reason reason,
			WorkChange.WeatherReason weatherReason, String detail) {
		if (employerId <= 0 || assignmentId <= 0) throw WorkCancellationException.invalid();
		// 서비스 외 내부 호출도 같은 정규화·500 코드 포인트·사유 조합 검증을 적용한다.
		var input = CancellationInput.reason(reason, weatherReason, detail);
		members.findById(employerId).filter(member -> member.memberRole().orElse(null) == MemberRole.EMPLOYER)
			.orElseThrow(() -> new AccessDeniedException("Employer role required"));
		var ownership = assignments.findOwnership(assignmentId).orElseThrow(WorkCancellationException::notFound);
		if (ownership.getEmployerId() != employerId) throw WorkCancellationException.notFound();

		// 보험 폴링·사유 보완과 같은 근로자 스트림 → 확정 근무 → 변경 행 순서를 유지한다.
		// 완료 API는 확정 근무 → 변경 행만 잠그므로 취소와 완료도 같은 근무 행에서 직렬화된다.
		streams.ensureExists(ownership.getWorkerId());
		var stream = streams.lockWorker(ownership.getWorkerId()).orElseThrow(WorkCancellationException::unavailable);
		var assignment = assignments.findByIdForUpdate(assignmentId).orElseThrow(WorkCancellationException::notFound);
		if (assignment.getCompletedAtUtc() != null || assignment.getCompletedBy() != null) {
			throw WorkCancellationException.conflict("WORK_ALREADY_COMPLETED", "완료된 근무는 취소할 수 없습니다.");
		}

		var previous = changes.findForUpdate(assignmentId);
		if (previous.isPresent()) {
			var change = previous.get();
			if (change.getChangeType() == WorkChange.Type.EARLY_ENDED) {
				throw WorkCancellationException.conflict("WORK_ALREADY_EARLY_ENDED", "조기 종료된 근무는 시작 전 취소로 바꿀 수 없습니다.");
			}
			if (change.getReasonCode() != input.reason() || change.getWeatherReasonCode() != input.weather()
					|| !Objects.equals(CancellationInput.normalizeDetail(change.getReasonDetail()), input.detail())) {
				throw WorkCancellationException.conflict("CANCELLATION_REASON_CONFLICT",
					"이미 기록된 취소 내용과 다릅니다. 사유 보완 기능을 이용해 주세요.");
			}
			// 시작 시각이 지난 재요청도 원래 결과를 반환한다. 시각·버전·발행 순번을 갱신하지 않는다.
			return new RecordedCancellation(change, assignment.getAgreedTimeZone());
		}

		var zone = InsuranceTime.zone(assignment.getAgreedTimeZone());
		var start = InsuranceTime.local(assignment.getAgreedStartAt(), zone).toInstant();
		// 잠금 대기가 끝난 실제 서버 시각으로 검사한다. 정확히 약정 시작 시각이면 거절한다.
		var instant = clock.instant();
		if (!instant.isBefore(start)) {
			throw WorkCancellationException.conflict("WORK_ALREADY_STARTED", "약정 근무 시작 시각 전까지만 취소할 수 있습니다.");
		}
		var now = LocalDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
		var change = changes.saveAndFlush(new WorkChange(assignmentId, employerId, WorkChange.Type.CANCELLED,
			input.reason(), input.weather(), input.detail(), now, now));
		versions.save(new WorkChangeVersion(ownership.getWorkerId(), stream.nextSequence(), change, assignment.getAgreedTimeZone()));
		return new RecordedCancellation(change, assignment.getAgreedTimeZone());
	}

	public record RecordedCancellation(WorkChange change, String timeZone) {}

	// 내부 시연·검증된 변경 저장용이다. 정식 시작 전 취소 API는 cancelBeforeStart를 사용한다.
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
