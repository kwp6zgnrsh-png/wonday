package com.wonday.farm.change.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import com.wonday.farm.change.entity.WorkChange;
import com.wonday.farm.change.repository.WorkChangeRepository;
import com.wonday.farm.insurance.entity.WorkChangeVersion;
import com.wonday.farm.insurance.exception.InsuranceApiException;
import com.wonday.farm.insurance.repository.ChangeStreamRepository;
import com.wonday.farm.insurance.repository.WorkChangeVersionRepository;
import com.wonday.farm.insurance.service.InsuranceTime;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import com.wonday.farm.work.repository.WorkAssignmentRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

// 후속 CHANGE 기능에서 업무 검증을 마친 뒤 호출할 저장 경계다. 취소·조기 종료용 사용자 API는 아직 제공하지 않는다.
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

	// 실제 작업 시작 판단은 미정이다. 호출자가 먼저 취소/조기 종료 가능 여부를 검증해야 한다.
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
		if (changes.findForUpdate(assignmentId).isPresent()) {
			throw new InsuranceApiException(HttpStatus.CONFLICT, "WORK_CHANGE_ALREADY_EXISTS", "이미 근무 변경이 기록되어 있습니다.");
		}
		var assignment = assignments.findOwned(ownership.getWorkerId(), assignmentId)
			.orElseThrow(InsuranceApiException::unavailable);
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
		var change = changes.findForUpdate(assignmentId).orElseThrow(() ->
			new InsuranceApiException(HttpStatus.NOT_FOUND, "WORK_CHANGE_NOT_FOUND", "근무 변경 기록을 찾을 수 없습니다."));
		var assignment = assignments.findOwned(ownership.getWorkerId(), assignmentId)
			.orElseThrow(InsuranceApiException::unavailable);
		InsuranceTime.zone(assignment.getAgreedTimeZone());
		change.revise(reason, weatherReason, detail,
			LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC));
		versions.save(new WorkChangeVersion(ownership.getWorkerId(), stream.nextSequence(), change, assignment.getAgreedTimeZone()));
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
