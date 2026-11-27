package com.wonday.farm.work.service;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import com.wonday.farm.change.entity.WorkChange;
import com.wonday.farm.change.repository.WorkChangeRepository;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import com.wonday.farm.work.dto.WorkCompletionResponse;
import com.wonday.farm.work.entity.WorkAssignment;
import com.wonday.farm.work.exception.WorkCompletionException;
import com.wonday.farm.work.repository.WorkAssignmentRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkCompletionService {
	private final MemberRepository members;
	private final WorkAssignmentRepository assignments;
	private final WorkChangeRepository changes;
	private final Clock clock;

	public WorkCompletionService(MemberRepository members, WorkAssignmentRepository assignments,
			WorkChangeRepository changes, @Qualifier("workCompletionClock") Clock clock) {
		this.members = members;
		this.assignments = assignments;
		this.changes = changes;
		this.clock = clock;
	}

	@Transactional(isolation = Isolation.READ_COMMITTED)
	public WorkCompletionResponse complete(long employerId, long assignmentId) {
		if (assignmentId <= 0) throw WorkCompletionException.invalidRequest();
		members.findById(employerId).filter(member -> member.memberRole().orElse(null) == MemberRole.EMPLOYER)
			.orElseThrow(() -> new AccessDeniedException("Employer role required"));
		var ownership = assignments.findOwnership(assignmentId).orElseThrow(WorkCompletionException::notFound);
		// 다른 고용주의 근무인지 여부까지 알려주지 않는다. 클라이언트의 고용주 ID는 받지 않는다.
		if (ownership.getEmployerId() != employerId) throw WorkCompletionException.notFound();

		// 취소 기록이 아직 없는 경우에도 잠글 수 있는 확정 근무 행을 공통 잠금으로 사용한다.
		// 취소·조기 종료 저장도 같은 행 → 변경 행 순서로 잠그므로 두 상태를 함께 저장할 수 없다.
		var assignment = assignments.findByIdForUpdate(assignmentId).orElseThrow(WorkCompletionException::notFound);
		var change = changes.findForUpdate(assignmentId);
		if (change.isPresent()) {
			String code = change.get().getChangeType() == WorkChange.Type.CANCELLED
				? "WORK_ALREADY_CANCELLED" : "WORK_ALREADY_EARLY_ENDED";
			throw new WorkCompletionException(HttpStatus.CONFLICT, code, "취소되거나 조기 종료된 근무는 완료할 수 없습니다.");
		}

		var zone = agreedZone(assignment.getAgreedTimeZone());
		if ((assignment.getCompletedAtUtc() == null) != (assignment.getCompletedBy() == null)) {
			throw WorkCompletionException.unavailable();
		}
		// 잠금을 기다린 재요청도 최초 완료 시각과 처리자를 그대로 사용한다.
		if (assignment.getCompletedAtUtc() != null) return response(assignment, zone);

		var end = assignment.getAgreedEndAt();
		if (end == null || zone.getRules().getValidOffsets(end).size() != 1) {
			throw WorkCompletionException.unavailable();
		}
		var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
		if (now.isBefore(end.atZone(zone).toInstant())) {
			throw new WorkCompletionException(HttpStatus.CONFLICT, "WORK_NOT_FINISHED", "약정 근무 종료 시각 이후에 완료할 수 있습니다.");
		}

		// JPA 변경 감지로 한 트랜잭션에서 저장한다. 약정·지원·취소 이력과 보험 변경 피드는 건드리지 않는다.
		assignment.complete(LocalDateTime.ofInstant(now, ZoneOffset.UTC), employerId);
		return response(assignment, zone);
	}

	private static ZoneId agreedZone(String value) {
		if (value == null || value.isBlank()) throw WorkCompletionException.unavailable();
		try {
			return ZoneId.of(value);
		} catch (DateTimeException exception) {
			throw WorkCompletionException.unavailable();
		}
	}

	private static WorkCompletionResponse response(WorkAssignment assignment, ZoneId zone) {
		return new WorkCompletionResponse(assignment.getId(),
			assignment.getCompletedAtUtc().toInstant(ZoneOffset.UTC).atZone(zone).toOffsetDateTime());
	}
}