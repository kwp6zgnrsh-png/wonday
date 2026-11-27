package com.wonday.farm.application.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.LocalDateTime;
import java.util.Arrays;

import com.wonday.farm.application.repository.ApplicationRequestLockRepository;
import com.wonday.farm.application.repository.JobApplicationRepository;
import com.wonday.farm.job.repository.JobPostingRepository;
import jakarta.persistence.Column;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.test.util.ReflectionTestUtils;

// 엔티티 생성·회차 해제 방어와 매핑·잠금·SQL 선언. V12의 CHECK·UNIQUE(현재 회차 생성 컬럼)·FK가 실제 MySQL에서
// 동작하는지, @Lock이 실제 FOR UPDATE로 실행되는지, 네이티브 SQL의 JOIN 결과는 증명하지 않는다.
@DisplayName("APP-02 지원·성공 기록 엔티티와 저장소 선언 (단위)")
class ApplicationEntityTest {
	private static final LocalDateTime NOW = LocalDateTime.parse("2026-12-01T01:02:03.456789");

	@Test
	@DisplayName("새 지원: PENDING·current=true·접수 버전·두 접수 시각 모두 UTC 값")
	void newApplication() {
		var app = new JobApplication(5, 201, 2, 3, NOW);

		assertThat(app.getWorkerId()).isEqualTo(5);
		assertThat(app.getWorkScheduleId()).isEqualTo(201);
		assertThat(app.getApplicationRound()).isEqualTo(2);
		assertThat(app.getAppliedTermsVersion()).isEqualTo(3);
		assertThat(app.getStatus()).isEqualTo("PENDING");
		assertThat(app.isCurrent()).isTrue();
		assertThat(app.getAppliedAt()).isEqualTo(NOW);
		assertThat(app.getAppliedAtUtc()).isEqualTo(NOW);
		assertThat(app.getId()).isNull();
	}

	@Test
	@DisplayName("0 이하 근로자·일정·회차·버전 또는 null 시각은 IllegalArgumentException")
	void invalidArguments() {
		assertThatThrownBy(() -> new JobApplication(0, 201, 1, 1, NOW)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new JobApplication(5, 0, 1, 1, NOW)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new JobApplication(5, 201, 0, 1, NOW)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new JobApplication(5, 201, 1, -1, NOW)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new JobApplication(5, 201, 1, 1, null)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("재지원 해제는 current만 false로 바꾸고 상태·시각·회차·버전·ID를 유지 (두 번 호출해도 같음)")
	void retireKeepsHistory() {
		var app = new JobApplication(5, 201, 1, 1, NOW);
		ReflectionTestUtils.setField(app, "id", 500L);
		ReflectionTestUtils.setField(app, "status", "NOT_SELECTED");

		app.retireForReapplication();
		app.retireForReapplication();

		assertThat(app.isCurrent()).isFalse();
		assertThat(app.getStatus()).isEqualTo("NOT_SELECTED");
		assertThat(app.getAppliedAt()).isEqualTo(NOW);
		assertThat(app.getApplicationRound()).isEqualTo(1);
		assertThat(app.getAppliedTermsVersion()).isEqualTo(1);
		assertThat(app.getId()).isEqualTo(500L);
	}

	@Test
	@DisplayName("지원 엔티티의 공개 변경 메서드는 retireForReapplication 하나 (상태 변경·삭제 메서드 없음)")
	void onlyRetireMutator() {
		assertThat(publicNonGetters(JobApplication.class)).containsExactly("retireForReapplication");
	}

	@Test
	@DisplayName("성공 기록: 생성자 값 그대로, 모든 컬럼 updatable=false, 공개 변경 메서드 없음, 만료·삭제 필드 없음")
	void receipt() {
		var receipt = new ApplicationReceipt(5, "key", 101, "f".repeat(64), "{}", NOW);
		assertThat(receipt.getWorkerId()).isEqualTo(5);
		assertThat(receipt.getRequestKey()).isEqualTo("key");
		assertThat(receipt.getJobPostingId()).isEqualTo(101);
		assertThat(receipt.getRequestFingerprint()).hasSize(64);
		assertThat(receipt.getResponsePayload()).isEqualTo("{}");
		assertThat(receipt.getCreatedAtUtc()).isEqualTo(NOW);
		assertThat(publicNonGetters(ApplicationReceipt.class)).isEmpty();
		assertThat(Arrays.stream(ApplicationReceipt.class.getDeclaredFields()).filter(f -> f.isAnnotationPresent(Column.class))
			.map(f -> f.getAnnotation(Column.class).updatable())).hasSize(6).containsOnly(false);
		assertThat(Arrays.stream(ApplicationReceipt.class.getDeclaredFields()).map(f -> f.getName().toLowerCase()))
			.noneMatch(name -> name.contains("expire") || name.contains("deleted") || name.contains("status"));
	}

	@Test
	@DisplayName("잠금 선언: 근로자 요청 잠금·공고·현재 지원은 PESSIMISTIC_WRITE (실제 FOR UPDATE 실행 아님)")
	void lockDeclarations() throws Exception {
		assertThat(lockOf(ApplicationRequestLockRepository.class.getMethod("lockWorker", long.class))).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
		assertThat(lockOf(JobPostingRepository.class.getMethod("findByIdForUpdate", long.class))).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
		assertThat(lockOf(JobApplicationRepository.class.getMethod("findCurrentForUpdate", long.class, long.class)))
			.isEqualTo(LockModeType.PESSIMISTIC_WRITE);
		assertThat(query(JobApplicationRepository.class.getMethod("findCurrentForUpdate", long.class, long.class))).contains("app.current = true");
		assertThat(query(ApplicationRequestLockRepository.class.getMethod("ensureExists", long.class)))
			.contains("insert into application_request_lock (worker_id) values (:workerId)", "on duplicate key update worker_id = worker_id");
	}

	@Test
	@DisplayName("SQL 선언: 최대 회차는 coalesce(max, 0), 차단 근무는 과거 회차 포함·취소 제외·완료 포함, 취소 근무는 미완료 CANCELLED만")
	void sqlDeclarations() throws Exception {
		assertThat(query(JobApplicationRepository.class.getMethod("maxRound", long.class, long.class)))
			.contains("coalesce(max(app.applicationRound), 0)").doesNotContain("current");
		String blocking = query(JobApplicationRepository.class.getMethod("findBlockingAssignment", long.class, long.class));
		assertThat(blocking).contains("where app.worker_id = :workerId and app.work_schedule_id = :scheduleId",
			"change_record.id is null or change_record.change_type <> 'CANCELLED'",
			"or assignment.completed_at_utc is not null or assignment.completed_by is not null").doesNotContain("is_current");
		assertThat(query(JobApplicationRepository.class.getMethod("findCancelledAssignment", long.class)))
			.contains("change_record.change_type = 'CANCELLED'", "assignment.completed_at_utc is null and assignment.completed_by is null");
	}

	private static LockModeType lockOf(Method method) { return method.getAnnotation(Lock.class).value(); }

	private static String query(Method method) { return method.getAnnotation(Query.class).value().replaceAll("\\s+", " "); }

	private static java.util.List<String> publicNonGetters(Class<?> type) {
		return Arrays.stream(type.getDeclaredMethods()).filter(m -> Modifier.isPublic(m.getModifiers()) && !Modifier.isStatic(m.getModifiers()))
			.map(Method::getName).filter(name -> !name.startsWith("get") && !name.startsWith("is")).toList();
	}
}
