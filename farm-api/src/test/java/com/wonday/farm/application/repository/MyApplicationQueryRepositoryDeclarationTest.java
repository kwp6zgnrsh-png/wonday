package com.wonday.farm.application.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

// SQL 선언(문자열)만 확인한다. 실제 MySQL 실행 결과·JOIN 중복·인덱스·정렬 결과는 확인하지 않는다.
@DisplayName("APP-04 조회 SQL 선언 (문자열 검사)")
class MyApplicationQueryRepositoryDeclarationTest {
	private static String sql(String name) {
		Method method = Arrays.stream(MyApplicationQueryRepository.class.getDeclaredMethods())
			.filter(m -> m.getName().equals(name)).findFirst().orElseThrow();
		Query query = method.getAnnotation(Query.class);
		assertThat(query.nativeQuery()).isTrue();
		return query.value().replaceAll("\\s+", " ").trim();
	}

	@Test
	@DisplayName("모든 조회에 worker_id 조건, 쓰기(@Modifying·UPDATE·DELETE·INSERT) 없음")
	void ownerEverywhere() {
		for (String name : new String[] {"findCurrent", "findOwned", "findPast", "findCurrentId"}) {
			String value = sql(name);
			assertThat(value).as(name).contains("app.worker_id = :workerId");
			assertThat(value.toLowerCase()).as(name).doesNotContain("update ", "delete ", "insert ");
		}
		assertThat(Arrays.stream(MyApplicationQueryRepository.class.getDeclaredMethods())
			.noneMatch(m -> m.isAnnotationPresent(Modifying.class))).isTrue();
	}

	@Test
	@DisplayName("현재 목록: 현재 회차만, 필터는 표시 상태식, 정렬 경계(시각·ID), 최근순·ID 내림차순, limit 파라미터")
	void current() {
		String value = sql("findCurrent");
		assertThat(value).contains("app.is_current = 1",
			"(:filter = 'ALL' or (",
			"coalesce(app.applied_at_utc, app.applied_at) < :beforeAt",
			"(coalesce(app.applied_at_utc, app.applied_at) = :beforeAt and app.id < :beforeId)",
			"order by coalesce(app.applied_at_utc, app.applied_at) desc, app.id desc",
			"limit :fetchLimit");
		assertThat(value).doesNotContain("is_current = 0");
	}

	@Test
	@DisplayName("과거 회차: 같은 근로자·같은 일정·is_current=0, 회차 내림차순·ID 내림차순, 회차·ID 경계")
	void past() {
		String value = sql("findPast");
		assertThat(value).contains("app.work_schedule_id = :scheduleId and app.is_current = 0",
			"app.application_round < :beforeRound",
			"(app.application_round = :beforeRound and app.id < :beforeId)",
			"order by app.application_round desc, app.id desc", "limit :fetchLimit");
	}

	@Test
	@DisplayName("단건·현재 ID: 본인 조건 + ID / 일정 + 현재 회차")
	void ownedAndCurrentId() {
		assertThat(sql("findOwned")).endsWith("where app.worker_id = :workerId and app.id = :applicationId");
		assertThat(sql("findCurrentId")).contains("app.work_schedule_id = :scheduleId and app.is_current = 1");
	}

	@Test
	@DisplayName("Boolean 숫자 변환·지원 버전에 대응하는 조건 이력 JOIN·정렬 시각")
	void projection() {
		String value = MyApplicationQueryRepository.SELECT_ROW.replaceAll("\\s+", " ");
		assertThat(value).contains("cast(app.is_current as signed) as currentFlag",
			"coalesce(app.applied_at_utc, app.applied_at) as sortAppliedAt",
			"app.applied_at as legacyAppliedAt", "app.applied_at_utc as appliedAtUtc");
		assertThat(MyApplicationQueryRepository.FROM_ROW.replaceAll("\\s+", " ")).contains(
			"left join work_schedule_revision revision on revision.work_schedule_id = schedule.id and revision.terms_version = app.applied_terms_version",
			"left join work_assignment assignment on assignment.application_id = app.id",
			"left join work_change change_record on change_record.assignment_id = assignment.id");
		// V6 이력에는 좌표·작업 내용 컬럼이 없다: 조회도 하지 않는다.
		assertThat(value).doesNotContain("revision.latitude", "revision.longitude", "revision.description");
	}

	@Test
	@DisplayName("표시 상태식: 개인 취소 → 조기 종료 → 완료 → 확정 → 모집 취소 → 대기 → 미선정 → UNKNOWN 순서, 종료 시각으로 완료 판정 없음")
	void displayStatus() {
		String value = MyApplicationQueryRepository.DISPLAY_STATUS.replaceAll("\\s+", " ");
		String[] order = {
			"when assignment.id is not null and change_record.change_type = 'CANCELLED' then 'CANCELLED'",
			"when assignment.id is not null and change_record.change_type = 'EARLY_ENDED' then 'EARLY_ENDED'",
			"when assignment.id is not null and assignment.completed_at_utc is not null then 'COMPLETED'",
			"when assignment.id is not null then 'CONFIRMED'",
			"when schedule.status = 'REMOVED' then 'CANCELLED'",
			"when app.status = 'PENDING' then 'PENDING'",
			"when app.status = 'NOT_SELECTED' then 'NOT_SELECTED'",
			"else 'UNKNOWN'"};
		int last = -1;
		for (String part : order) {
			int index = value.indexOf(part);
			assertThat(index).as(part).isGreaterThan(last);
			last = index;
		}
		assertThat(value).doesNotContain("planned_end_at", "agreed_end_at", "now()", "utc_timestamp", "'SELECTED'");
	}
}
