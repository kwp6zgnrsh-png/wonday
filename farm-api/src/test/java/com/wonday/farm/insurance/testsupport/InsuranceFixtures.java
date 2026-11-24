package com.wonday.farm.insurance.testsupport;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.wonday.farm.change.entity.WorkChange;
import com.wonday.farm.insurance.entity.ChangeStream;
import com.wonday.farm.insurance.entity.WorkChangeVersion;
import com.wonday.farm.member.Member;
import com.wonday.farm.member.MemberRole;
import com.wonday.farm.work.entity.WorkAssignment;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

// 보험 연동 단위 테스트용 엔티티 생성기. JPA 전용 protected 생성자를 쓰는 엔티티는 리플렉션으로 필드를 채운다.
// 값은 모두 시연용 가상 데이터다.
public final class InsuranceFixtures {
	public static final String ZONE = "GMT+9";

	private InsuranceFixtures() {}

	public static Member member(long id, MemberRole role) {
		var member = new Member("member-" + id, "hash", "회원 " + id, role);
		ReflectionTestUtils.setField(member, "id", id);
		return member;
	}

	public static Member memberWithRoleCode(long id, String roleCode) {
		var member = member(id, MemberRole.WORKER);
		ReflectionTestUtils.setField(member, "role", roleCode);
		return member;
	}

	// 2026-11-30 GMT+9 현지 08:00~17:00, 휴게 12:00~13:00, 확정 시각 UTC 2026-11-20T01:02:03.456789.
	public static WorkAssignment assignment(long id) {
		var assignment = BeanUtils.instantiateClass(WorkAssignment.class);
		ReflectionTestUtils.setField(assignment, "id", id);
		ReflectionTestUtils.setField(assignment, "applicationId", 900L + id);
		ReflectionTestUtils.setField(assignment, "confirmedAtUtc", LocalDateTime.parse("2026-11-20T01:02:03.456789"));
		ReflectionTestUtils.setField(assignment, "agreedWorkDate", LocalDate.parse("2026-11-30"));
		ReflectionTestUtils.setField(assignment, "agreedTimeZone", ZONE);
		ReflectionTestUtils.setField(assignment, "agreedWorkLocation", "약정 작업지");
		ReflectionTestUtils.setField(assignment, "agreedCropCode", "AGREED-CROP");
		ReflectionTestUtils.setField(assignment, "agreedWorkTypeCode", "AGREED-WORK");
		ReflectionTestUtils.setField(assignment, "agreedStartAt", LocalDateTime.parse("2026-11-30T08:00"));
		ReflectionTestUtils.setField(assignment, "agreedEndAt", LocalDateTime.parse("2026-11-30T17:00"));
		ReflectionTestUtils.setField(assignment, "agreedBreakStartAt", LocalDateTime.parse("2026-11-30T12:00"));
		ReflectionTestUtils.setField(assignment, "agreedBreakEndAt", LocalDateTime.parse("2026-11-30T13:00"));
		ReflectionTestUtils.setField(assignment, "agreedDailyWage", 120000L);
		return assignment;
	}

	public static ChangeStream stream(long workerId, long lastSequence) {
		var stream = BeanUtils.instantiateClass(ChangeStream.class);
		ReflectionTestUtils.setField(stream, "workerId", workerId);
		ReflectionTestUtils.setField(stream, "lastSequence", lastSequence);
		return stream;
	}

	public static WorkChange change(long id, long assignmentId, WorkChange.Type type, WorkChange.Reason reason,
			WorkChange.WeatherReason weather, String detail, LocalDateTime effectiveAt, LocalDateTime recordedAt) {
		var change = new WorkChange(assignmentId, 10L, type, reason, weather, detail, effectiveAt, recordedAt);
		ReflectionTestUtils.setField(change, "id", id);
		return change;
	}

	public static WorkChangeVersion version(long workerId, long sequence, WorkChange change) {
		return new WorkChangeVersion(workerId, sequence, change, ZONE);
	}
}
