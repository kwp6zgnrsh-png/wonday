package com.wonday.farm.job;

import static com.wonday.farm.job.JobPostingRequests.firstSchedule;
import static com.wonday.farm.job.JobPostingRequests.json;
import static com.wonday.farm.job.JobPostingRequests.schedule;
import static com.wonday.farm.job.JobPostingRequests.valid;
import static com.wonday.farm.job.JobPostingRequests.withOneSchedule;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import com.jayway.jsonpath.JsonPath;
import com.wonday.farm.member.Member;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import com.wonday.farm.testsupport.FarmAuthClient;
import com.wonday.farm.testsupport.MySqlTestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

// 운영 코드 결함 재현 테스트. 기대 동작은 사용자(검증 요청자)가 확정했다 (2026-11-24).
// 수정 전까지 실패한다. `./gradlew knownDefectTest`로 실행하며, 수정되면 known-defect 태그를 지워 일반 통합 테스트로 옮긴다.
@Tag("integration")
@Tag("known-defect")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(MySqlTestDatabase.class)
class JobPostingKnownDefectTests {
	private static final String PASSWORD = "known-defect-test";

	@LocalServerPort
	private int port;
	@Autowired
	private MemberRepository members;
	@Autowired
	private PasswordEncoder encoder;
	@Autowired
	private JdbcTemplate jdbc;

	private FarmAuthClient client;
	private FarmAuthClient.CsrfHeader csrf;

	@BeforeEach
	void loginAsEmployer() throws Exception {
		Member employer = members.save(new Member("defect-" + UUID.randomUUID(), encoder.encode(PASSWORD),
			"결함 재현 계정", MemberRole.EMPLOYER));
		client = new FarmAuthClient(port);
		assertThat(client.login(employer.getLoginId(), PASSWORD, client.csrf()).statusCode()).isEqualTo(204);
		csrf = client.csrf();
	}

	@AfterEach
	void close() {
		client.close();
	}

	@Nested
	@DisplayName("DEFECT-1 정수 필드의 소수·문자열이 400 없이 변환되어 저장된다")
	class NumberCoercion {
		@Test
		void fractionalHeadcountIsRejected() throws Exception {
			// 현재: 201, headcount 1.5 → 1로 잘려 저장
			assertRejectedAndNothingStored(edit(b -> b.put("headcount", 1.5)));
		}

		@Test
		void fractionalDailyWageIsRejected() throws Exception {
			// 현재: 201, dailyWage 120000.9 → 120000으로 잘려 저장
			assertRejectedAndNothingStored(edit(b -> b.put("dailyWage", 120000.9)));
		}

		@Test
		void numericStringHeadcountIsRejected() throws Exception {
			// 현재: 201, "2" → 2로 변환되어 저장
			assertRejectedAndNothingStored(edit(b -> b.put("headcount", "2")));
		}

		@Test
		void numericStringDailyWageIsRejected() throws Exception {
			assertRejectedAndNothingStored(edit(b -> b.put("dailyWage", "120000")));
		}
	}

	@Nested
	@DisplayName("DEFECT-2 일시의 Z(UTC) 표기가 무시되고 작업지 현지 일시로 저장된다")
	class UtcDesignatorIgnored {
		@Test
		void plannedStartWithZIsRejected() throws Exception {
			// 현재: 201, 08:00Z(=Asia/Seoul 17:00)가 Asia/Seoul 08:00으로 저장. +09:00 오프셋은 이미 400이다.
			assertRejectedAndNothingStored(edit(b -> firstSchedule(b).put("plannedStartAt", "2026-11-30T08:00:00Z")));
		}

		@Test
		void breakEndWithZIsRejected() throws Exception {
			assertRejectedAndNothingStored(edit(b -> firstSchedule(b).put("breakEndAt", "2026-11-30T13:00:00Z")));
		}
	}

	@Nested
	@DisplayName("DEFECT-3 작업지 시간대에 지역 ID가 아닌 값(고정 오프셋·UTC·옛 별칭)이 저장된다")
	class NonRegionTimeZone {
		@ParameterizedTest(name = "timeZone={0}")
		@ValueSource(strings = {"+09:00", "UTC", "GMT+9", "UTC+09:00", "Z", "ROK"})
		void nonRegionTimeZoneIsRejected(String timeZone) throws Exception {
			HttpResponse<String> response = postAndAssertNothingStored(edit(b -> b.put("timeZone", timeZone)));
			assertError(response, "INVALID_TIME_ZONE", "timeZone");
		}
	}

	@Nested
	@DisplayName("DEFECT-4 휴게시간 오류의 field가 문제가 있는 항목을 가리키지 않는다")
	class BreakErrorField {
		@Test
		void missingBreakEndPointsToBreakEndAt() throws Exception {
			// 현재: field = schedules[0].breakStartAt
			HttpResponse<String> response = postAndAssertNothingStored(withOneSchedule(
				schedule("2026-12-11", "2026-12-11T08:00:00", "2026-12-11T16:00:00", "2026-12-11T12:00:00", null)));
			assertError(response, "INVALID_BREAK_TIME", "schedules[0].breakEndAt");
		}

		@Test
		void breakStartingBeforeWorkPointsToBreakStartAt() throws Exception {
			// 현재: field = schedules[0].breakEndAt
			HttpResponse<String> response = postAndAssertNothingStored(withOneSchedule(
				schedule("2026-12-11", "2026-12-11T08:00:00", "2026-12-11T16:00:00", "2026-12-11T07:59:59", "2026-12-11T09:00:00")));
			assertError(response, "INVALID_BREAK_TIME", "schedules[0].breakStartAt");
		}
	}

	private void assertRejectedAndNothingStored(Map<String, Object> body) throws Exception {
		HttpResponse<String> response = postAndAssertNothingStored(body);
		assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
	}

	private HttpResponse<String> postAndAssertNothingStored(Map<String, Object> body) throws Exception {
		long postings = jdbc.queryForObject("SELECT COUNT(*) FROM job_posting", Long.class);
		long schedules = jdbc.queryForObject("SELECT COUNT(*) FROM work_schedule", Long.class);
		HttpResponse<String> response = client.post("/api/job-postings", "application/json", json(body), csrf);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM job_posting", Long.class))
			.as("job_posting rows (response %s %s)", response.statusCode(), response.body()).isEqualTo(postings);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM work_schedule", Long.class)).isEqualTo(schedules);
		return response;
	}

	private static void assertError(HttpResponse<String> response, String code, String field) {
		assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
		assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo(code);
		assertThat(JsonPath.<String>read(response.body(), "$.field")).isEqualTo(field);
	}

	private static Map<String, Object> edit(Consumer<Map<String, Object>> change) {
		Map<String, Object> body = valid();
		change.accept(body);
		return body;
	}
}
