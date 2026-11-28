package com.wonday.farm.work.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

// SQL 선언(문자열)만 확인한다. MySQL의 regexp_replace·마스킹·정렬·집계 실행 결과는 확인하지 않는다.
@DisplayName("WORK-01 지원자 조회 SQL 선언 (문자열 검사)")
class WorkApplicantRepositoryDeclarationTest {
	private static String sql(String name) {
		var method = Arrays.stream(WorkApplicantRepository.class.getDeclaredMethods()).filter(m -> m.getName().equals(name)).findFirst().orElseThrow();
		Query query = method.getAnnotation(Query.class);
		assertThat(query.nativeQuery()).as(name).isTrue();
		return query.value().replaceAll("\\s+", " ").trim();
	}

	@Test
	@DisplayName("모든 지원 조회에 고용주·공고·일정 범위, 쓰기 쿼리 없음")
	void scope() {
		for (String name : new String[] {"findCurrent", "findCurrentOwned", "findOwnedAnchor", "findContact"}) {
			assertThat(sql(name)).as(name).contains("posting.employer_id = :employerId and posting.id = :postingId")
				.containsPattern("schedule.id = :scheduleId");
		}
		assertThat(sql("findOwnedPosting")).isEqualTo("select id from job_posting where id = :postingId and employer_id = :employerId");
		assertThat(sql("findOwnedSchedule")).contains("posting.employer_id = :employerId and posting.id = :postingId and schedule.id = :scheduleId");
		assertThat(Arrays.stream(WorkApplicantRepository.class.getDeclaredMethods()).noneMatch(m -> m.isAnnotationPresent(Modifying.class))).isTrue();
	}

	@Test
	@DisplayName("목록: 현재 회차만, 지원 상태 필터, 지원 시각·ID 오름차순, 앵커 이후(>) 경계, limit")
	void current() {
		String value = sql("findCurrent");
		assertThat(value).contains("and app.is_current = 1 and (:filter = 'ALL' or app.status = :filter)",
			"coalesce(app.applied_at_utc, app.applied_at) > :afterAt",
			"(coalesce(app.applied_at_utc, app.applied_at) = :afterAt and app.id > :afterId)",
			"order by coalesce(app.applied_at_utc, app.applied_at), app.id limit :fetchLimit");
		assertThat(value).doesNotContainPattern("(?i)\\bdesc\\b");
	}

	@Test
	@DisplayName("상세는 현재 회차 조건, 앵커는 회차 조건 없음(과거 회차가 돼도 원래 시각 유지)")
	void detailAndAnchor() {
		assertThat(sql("findCurrentOwned")).endsWith("and app.id = :applicationId and app.is_current = 1");
		assertThat(sql("findOwnedAnchor")).endsWith("and app.id = :applicationId").doesNotContain("is_current = 1 and app.id");
	}

	@Test
	@DisplayName("목록·상세 projection: 전체 번호(worker.phone as) 없이 마스킹·상태만, 민감 컬럼 미조회")
	void projection() {
		String select = WorkApplicantRepository.SELECT.replaceAll("\\s+", " ");
		assertThat(select).doesNotContainPattern("worker\\.phone\\s+as").contains(" as maskedPhone", " as phoneState", "worker.name as workerName");
		for (String name : new String[] {"findCurrent", "findCurrentOwned", "findOwnedAnchor", "findContact"}) {
			assertThat(sql(name).toLowerCase()).as(name).doesNotContain("resident_registration", "identity_", "hmac", "account_number",
				"bank_code", "password");
		}
	}

	@Test
	@DisplayName("마스킹 식: 하이픈·공백 제거 → 10~11자리일 때만 앞3-별표(길이-7)-끝4, 그 외 null (11자리 4개·10자리 3개 별표)")
	void maskExpression() {
		String select = WorkApplicantRepository.SELECT.replaceAll("\\s+", " ");
		String phone = WorkApplicantRepository.PHONE;
		assertThat(phone).isEqualTo("regexp_replace(coalesce(worker.phone, ''), '[-[:space:]]', '')");
		assertThat(select).contains("case when " + phone + " regexp '^[0-9]{10,11}$' then concat(left(" + phone + ",3), '-', repeat('*', char_length("
			+ phone + ")-7), '-', right(" + phone + ",4)) else null end as maskedPhone");
		assertThat(WorkApplicantRepository.PHONE_STATE).isEqualTo("case when " + phone + " = '' then 'NOT_REGISTERED' when " + phone
			+ " regexp '^[0-9]{10,11}$' then 'AVAILABLE' else 'INVALID_FORMAT' end");
		// 같은 식을 Java로 옮긴 계산(선언 해석). MySQL 실행 결과가 아니다.
		assertThat(mask("01000001001")).isEqualTo("010-****-1001");
		assertThat(mask("0212345678")).isEqualTo("021-***-5678");
	}

	private static String mask(String digits) {
		return digits.substring(0, 3) + "-" + "*".repeat(digits.length() - 7) + "-" + digits.substring(digits.length() - 4);
	}

	@Test
	@DisplayName("연락처 쿼리: 현재 회차·PENDING/SELECTED일 때만 worker.phone, 그 외 null (서비스도 별도 재검사)")
	void contact() {
		String value = sql("findContact");
		assertThat(value).contains("case when app.is_current = 1 and app.status in ('PENDING', 'SELECTED') then worker.phone else null end as phone",
			"cast(app.is_current as signed) as currentFlag", "join member worker on worker.id = app.worker_id");
		assertThat(Pattern.compile("worker\\.phone").matcher(value).results().count()).isEqualTo(1);
	}
}
