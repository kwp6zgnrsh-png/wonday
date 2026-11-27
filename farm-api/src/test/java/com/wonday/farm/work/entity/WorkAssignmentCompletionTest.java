package com.wonday.farm.work.entity;

import static com.wonday.farm.insurance.testsupport.InsuranceFixtures.assignment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.LocalDateTime;
import java.util.Arrays;

import com.wonday.farm.work.repository.WorkAssignmentRepository;
import jakarta.persistence.Column;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.jpa.repository.Query;
import org.springframework.test.util.ReflectionTestUtils;

// 엔티티 완료 메서드의 방어 동작과 매핑·잠금 선언. 실제 DATETIME(6) 저장, V11 CHECK(두 컬럼 동시 NULL/NOT NULL)·
// FK(completed_by → member) 동작과 PESSIMISTIC_WRITE의 실제 SELECT ... FOR UPDATE는 증명하지 않는다 (통합 검증 대상).
@DisplayName("확정 근무 엔티티 완료 기록 (단위)")
class WorkAssignmentCompletionTest {
	private static final LocalDateTime AT = LocalDateTime.parse("2026-11-30T09:00:00.123456");

	@Test
	@DisplayName("최초 완료는 시각과 처리자를 함께 기록한다")
	void firstCompletion() {
		var assignment = assignment(31L);

		assignment.complete(AT, 10L);

		assertThat(assignment.getCompletedAtUtc()).isEqualTo(AT);
		assertThat(assignment.getCompletedBy()).isEqualTo(10L);
	}

	@Test
	@DisplayName("이미 완료된 근무는 IllegalStateException이고 최초 값을 유지한다 (같은 값·다른 처리자 모두)")
	void secondCompletionIsRejected() {
		var assignment = assignment(31L);
		assignment.complete(AT, 10L);

		assertThatThrownBy(() -> assignment.complete(AT.plusDays(1), 11L)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> assignment.complete(AT, 10L)).isInstanceOf(IllegalStateException.class);
		assertThat(assignment.getCompletedAtUtc()).isEqualTo(AT);
		assertThat(assignment.getCompletedBy()).isEqualTo(10L);
	}

	@Test
	@DisplayName("시각만 또는 처리자만 있는 비정상 상태에서도 덮어쓰지 않는다")
	void halfStateIsNotOverwritten() {
		var onlyTime = assignment(31L);
		ReflectionTestUtils.setField(onlyTime, "completedAtUtc", AT);
		assertThatThrownBy(() -> onlyTime.complete(AT.plusHours(1), 10L)).isInstanceOf(IllegalStateException.class);
		assertThat(onlyTime.getCompletedAtUtc()).isEqualTo(AT);
		assertThat(onlyTime.getCompletedBy()).isNull();

		var onlyActor = assignment(31L);
		ReflectionTestUtils.setField(onlyActor, "completedBy", 10L);
		assertThatThrownBy(() -> onlyActor.complete(AT, 11L)).isInstanceOf(IllegalStateException.class);
		assertThat(onlyActor.getCompletedAtUtc()).isNull();
		assertThat(onlyActor.getCompletedBy()).isEqualTo(10L);
	}

	@Test
	@DisplayName("완료 시각 null은 IllegalArgumentException이고 아무 값도 기록하지 않는다")
	void nullTime() {
		var assignment = assignment(31L);

		assertThatThrownBy(() -> assignment.complete(null, 10L)).isInstanceOf(IllegalArgumentException.class);
		assertThat(assignment.getCompletedAtUtc()).isNull();
		assertThat(assignment.getCompletedBy()).isNull();
	}

	@ParameterizedTest(name = "employerId={0}")
	@ValueSource(longs = {0, -1, Long.MIN_VALUE})
	@DisplayName("0·음수 처리자는 IllegalArgumentException이고 시각도 기록하지 않는다")
	void nonPositiveEmployer(long employerId) {
		var assignment = assignment(31L);

		assertThatThrownBy(() -> assignment.complete(AT, employerId)).isInstanceOf(IllegalArgumentException.class);
		assertThat(assignment.getCompletedAtUtc()).isNull();
		assertThat(assignment.getCompletedBy()).isNull();
	}

	@Test
	@DisplayName("완료 후에도 약정 값은 그대로다")
	void agreementUnchanged() {
		var assignment = assignment(31L);

		assignment.complete(AT, 10L);

		assertThat(assignment.getAgreedStartAt()).isEqualTo(LocalDateTime.parse("2026-11-30T08:00"));
		assertThat(assignment.getAgreedEndAt()).isEqualTo(LocalDateTime.parse("2026-11-30T17:00"));
		assertThat(assignment.getAgreedTimeZone()).isEqualTo("GMT+9");
		assertThat(assignment.getAgreedDailyWage()).isEqualTo(120000L);
		assertThat(assignment.getConfirmedAtUtc()).isEqualTo(LocalDateTime.parse("2026-11-20T01:02:03.456789"));
		assertThat(assignment.getApplicationId()).isEqualTo(931L);
	}

	@Test
	@DisplayName("완료 취소·정정용 공개 변경 메서드가 없다: getter 외 공개 인스턴스 메서드는 complete 하나 (Object 메서드 제외)")
	void noUndoOrCorrection() {
		assertThat(Arrays.stream(WorkAssignment.class.getDeclaredMethods())
			.filter(m -> Modifier.isPublic(m.getModifiers()) && !Modifier.isStatic(m.getModifiers()))
			.map(Method::getName).filter(name -> !name.startsWith("get")))
			.containsExactly("complete");
	}

	@Test
	@DisplayName("컬럼 매핑: completed_at_utc(LocalDateTime), completed_by(Long, null 허용) (선언 확인)")
	void columnMapping() throws Exception {
		var time = WorkAssignment.class.getDeclaredField("completedAtUtc");
		var actor = WorkAssignment.class.getDeclaredField("completedBy");
		assertThat(time.getAnnotation(Column.class).name()).isEqualTo("completed_at_utc");
		assertThat(time.getType()).isEqualTo(LocalDateTime.class);
		assertThat(actor.getAnnotation(Column.class).name()).isEqualTo("completed_by");
		assertThat(actor.getType()).isEqualTo(Long.class);
	}

	@Test
	@DisplayName("findByIdForUpdate는 id 단건 JPQL + PESSIMISTIC_WRITE 선언 (실제 FOR UPDATE 실행 아님)")
	void lockDeclaration() throws Exception {
		var method = WorkAssignmentRepository.class.getMethod("findByIdForUpdate", long.class);
		var lock = method.getAnnotation(org.springframework.data.jpa.repository.Lock.class);
		assertThat(lock.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
		assertThat(method.getAnnotation(Query.class).value())
			.isEqualTo("select assignment from WorkAssignment assignment where assignment.id = :assignmentId");
		assertThat(method.getAnnotation(Query.class).nativeQuery()).isFalse();
	}
}
