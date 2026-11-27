package com.wonday.farm.job.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

// APP-02로 바뀐 조회·집계 SQL의 문자열 선언 확인이다. 실제 MySQL에서 JOIN·DISTINCT·is_current 필터·projection·
// 정렬 결과가 맞는지는 실행하지 않았다 (통합 검증 대상). 집계 누락 0 처리와 응답 유지는 기존
// JobPostingQueryServiceTest·JobPostingQueryServiceMissingCountsTest·WorkerJobPostingQueryServiceTest 실행 결과로 본다.
@DisplayName("APP-02 지원 회차 관련 조회·집계 SQL 선언 (문자열 검사)")
class ApplicationRoundQueryDeclarationTest {
	private static String sql(String name, Class<?>... types) throws Exception {
		return WorkScheduleRepository.class.getMethod(name, types).getAnnotation(Query.class).value().replaceAll("\\s+", " ");
	}

	@Test
	@DisplayName("APP-01 본인 지원 조회는 현재 회차(app.is_current = 1)만 읽고 본인 worker_id로 제한")
	void workerApplicationsCurrentOnly() throws Exception {
		assertThat(sql("findWorkerApplications", long.class, Collection.class))
			.contains("where app.worker_id = :workerId and app.work_schedule_id in (:scheduleIds) and app.is_current = 1")
			.doesNotContain("member", "phone", "identity");
	}

	@Test
	@DisplayName("JOB-05 지원자 수는 count(distinct application.worker_id), 확정 인원은 CANCELLED 제외(미취소·EARLY_ENDED 포함)")
	void ownedScheduleCounts() throws Exception {
		assertThat(sql("countOwnedSchedules", long.class))
			.contains("count(distinct application.worker_id) as applicantCount",
				"count(distinct case when change_record.id is null or change_record.change_type <> 'CANCELLED' then assignment.id end) as confirmedCount",
				"left join work_change change_record on change_record.assignment_id = assignment.id")
			.doesNotContain("count(distinct application.id)", "is_current");
	}

	@Test
	@DisplayName("지원 정원 판단은 확정 근무만 센다: PENDING 지원 행 자체는 세지 않고 CANCELLED 근무 제외")
	void occupyingAssignments() throws Exception {
		assertThat(sql("countOccupyingAssignments", long.class))
			.startsWith("select count(*) from work_assignment assignment")
			.contains("(change_record.id is null or change_record.change_type <> 'CANCELLED')")
			.doesNotContain("app.status", "PENDING");
		assertThat(sql("countWorkerScheduleAssignments", Collection.class)).contains("change_record.change_type <> 'CANCELLED'");
	}
}
