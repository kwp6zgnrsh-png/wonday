package com.wonday.farm.job;

import static com.wonday.farm.job.JobPostingRequests.firstSchedule;
import static com.wonday.farm.job.JobPostingRequests.json;
import static com.wonday.farm.job.JobPostingRequests.schedule;
import static com.wonday.farm.job.JobPostingRequests.valid;
import static com.wonday.farm.job.JobPostingRequests.withOneSchedule;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.jayway.jsonpath.JsonPath;
import com.wonday.farm.member.Member;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import com.wonday.farm.testsupport.FarmAuthClient;
import com.wonday.farm.testsupport.FarmAuthClient.CsrfHeader;
import com.wonday.farm.testsupport.MySqlTestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

// 공고 등록 API를 실제 HTTP(필터·서블릿 오류 처리 포함)로 호출하고, 결과를 격리된 MySQL에서 직접 조회해 검증한다.
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(MySqlTestDatabase.class)
class JobPostingHttpTests {
	static final String PATH = "/api/job-postings";
	private static final String PASSWORD = "job-posting-test";
	private static final DateTimeFormatter DB_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS");

	@LocalServerPort
	private int port;
	@Autowired
	private MemberRepository members;
	@Autowired
	private PasswordEncoder encoder;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private JobPostingRepository postings;

	private final List<FarmAuthClient> clients = new ArrayList<>();

	@AfterEach
	void closeClients() {
		clients.forEach(FarmAuthClient::close);
	}

	@Nested
	class Registration {
		@Test
		void twoDatesCreateOnePostingAndTwoLinkedSchedules() throws Exception {
			Session employer = login(MemberRole.EMPLOYER);

			HttpResponse<String> response = employer.post(valid());

			assertThat(response.statusCode()).isEqualTo(201);
			long postingId = longAt(response, "$.jobPostingId");
			assertThat(count("SELECT COUNT(*) FROM job_posting WHERE employer_id = ?", employer.id())).isEqualTo(1);
			List<Map<String, Object>> rows = jdbc.queryForList(
				"SELECT id, job_posting_id, work_date, status, terms_version FROM work_schedule"
					+ " WHERE job_posting_id = ? ORDER BY work_date", postingId);
			assertThat(rows).hasSize(2);
			assertThat(rows).extracting(row -> row.get("id")).doesNotHaveDuplicates();
			assertThat(rows).allSatisfy(row -> {
				assertThat(((Number) row.get("job_posting_id")).longValue()).isEqualTo(postingId);
				assertThat(row.get("status")).isEqualTo("OPEN");
				assertThat(((Number) row.get("terms_version")).intValue()).isEqualTo(1);
			});
		}

		@Test
		void responseMatchesStoredRows() throws Exception {
			Session employer = login(MemberRole.EMPLOYER);

			HttpResponse<String> response = employer.post(valid());

			long postingId = longAt(response, "$.jobPostingId");
			assertThat(longAt(response, "$.employerId")).isEqualTo(employer.id());
			assertThat(longAt(response, "$.headcount")).isEqualTo(
				count("SELECT default_headcount FROM job_posting WHERE id = ?", postingId));
			assertThat(longAt(response, "$.employerId")).isEqualTo(
				count("SELECT employer_id FROM job_posting WHERE id = ?", postingId));
			List<Map<String, Object>> responseSchedules = JsonPath.read(response.body(), "$.schedules");
			assertThat(responseSchedules).hasSize(2);
			for (Map<String, Object> schedule : responseSchedules) {
				Map<String, Object> row = jdbc.queryForMap(
					"SELECT job_posting_id, DATE_FORMAT(work_date, '%Y-%m-%d') work_date, status FROM work_schedule WHERE id = ?",
					((Number) schedule.get("workScheduleId")).longValue());
				assertThat(((Number) row.get("job_posting_id")).longValue()).isEqualTo(postingId);
				assertThat(row.get("work_date")).isEqualTo(schedule.get("workDate"));
				assertThat(row.get("status")).isEqualTo(schedule.get("status"));
			}
		}

		@Test
		void storesCommonConditionsOnPostingAndScheduleTimesExactly() throws Exception {
			Session employer = login(MemberRole.EMPLOYER);
			Map<String, Object> body = valid();

			long postingId = longAt(employer.post(body), "$.jobPostingId");

			Map<String, Object> posting = jdbc.queryForMap("SELECT * FROM job_posting WHERE id = ?", postingId);
			assertThat(posting).containsEntry("title", body.get("title"))
				.containsEntry("description", body.get("description"))
				.containsEntry("crop_code", body.get("cropCode"))
				.containsEntry("work_type_code", body.get("workTypeCode"))
				.containsEntry("work_location", body.get("workLocation"))
				.containsEntry("time_zone", body.get("timeZone"));
			// 정원·일급은 날짜별이 아니라 공고의 공통 조건으로 한 번만 저장된다.
			assertThat(((Number) posting.get("default_headcount")).intValue()).isEqualTo(2);
			assertThat(((Number) posting.get("default_daily_wage")).longValue()).isEqualTo(120000L);
			assertThat(jdbc.queryForList("SHOW COLUMNS FROM work_schedule").stream().map(c -> c.get("Field")))
				.doesNotContain("headcount", "daily_wage");

			// JVM(Asia/Seoul)과 DB(UTC)의 시간대가 달라도 현지 일시가 변환 없이 그대로 저장된다.
			@SuppressWarnings("unchecked")
			List<Map<String, Object>> sent = (List<Map<String, Object>>) body.get("schedules");
			List<Map<String, Object>> stored = scheduleTimes(postingId);
			for (int i = 0; i < sent.size(); i++) {
				assertThat(stored.get(i).get("plannedStartAt")).isEqualTo(dbTime(sent.get(i).get("plannedStartAt")));
				assertThat(stored.get(i).get("plannedEndAt")).isEqualTo(dbTime(sent.get(i).get("plannedEndAt")));
				assertThat(stored.get(i).get("breakStartAt")).isEqualTo(dbTime(sent.get(i).get("breakStartAt")));
				assertThat(stored.get(i).get("breakEndAt")).isEqualTo(dbTime(sent.get(i).get("breakEndAt")));
			}
		}

		@Test
		void scheduleWithoutBreakStoresNullBreaks() throws Exception {
			Session employer = login(MemberRole.EMPLOYER);

			long postingId = longAt(employer.post(withOneSchedule(
				schedule("2026-12-02", "2026-12-02T08:00:00", "2026-12-02T12:00:00", null, null))), "$.jobPostingId");

			assertThat(scheduleTimes(postingId).getFirst()).containsEntry("breakStartAt", null).containsEntry("breakEndAt", null);
		}

		@Test
		void overnightShiftEndingNextDayIsAccepted() throws Exception {
			Session employer = login(MemberRole.EMPLOYER);

			long postingId = longAt(employer.post(withOneSchedule(
				schedule("2026-12-05", "2026-12-05T22:00:00", "2026-12-06T06:00:00", "2026-12-06T01:00:00", "2026-12-06T02:00:00"))),
				"$.jobPostingId");

			assertThat(scheduleTimes(postingId).getFirst())
				.containsEntry("workDate", "2026-12-05")
				.containsEntry("plannedEndAt", "2026-12-06T06:00:00.000000");
		}

		@Test
		void breakTouchingWorkStartAndEndIsAccepted() throws Exception {
			Session employer = login(MemberRole.EMPLOYER);
			Map<String, Object> body = valid();
			body.put("schedules", new ArrayList<>(List.of(
				schedule("2026-12-07", "2026-12-07T08:00:00", "2026-12-07T16:00:00", "2026-12-07T08:00:00", "2026-12-07T09:00:00"),
				schedule("2026-12-08", "2026-12-08T08:00:00", "2026-12-08T16:00:00", "2026-12-08T15:00:00", "2026-12-08T16:00:00"))));

			assertThat(employer.post(body).statusCode()).isEqualTo(201);
		}

		@Test
		void microsecondPrecisionAndDatetimeRangeLimitsAreStoredExactly() throws Exception {
			Session employer = login(MemberRole.EMPLOYER);
			Map<String, Object> body = valid();
			body.put("schedules", new ArrayList<>(List.of(
				schedule("2026-12-09", "2026-12-09T08:00:00.123456", "2026-12-09T16:00:00.999999", null, null),
				schedule("1000-01-01", "1000-01-01T00:00:00", "1000-01-01T01:00:00", null, null),
				schedule("9999-12-31", "9999-12-31T22:00:00", "9999-12-31T23:59:59.999999", null, null))));

			long postingId = longAt(employer.post(body), "$.jobPostingId");

			List<Map<String, Object>> stored = scheduleTimes(postingId);
			assertThat(stored).extracting(row -> row.get("plannedStartAt")).containsExactly(
				"1000-01-01T00:00:00.000000", "2026-12-09T08:00:00.123456", "9999-12-31T22:00:00.000000");
			assertThat(stored).extracting(row -> row.get("plannedEndAt")).containsExactly(
				"1000-01-01T01:00:00.000000", "2026-12-09T16:00:00.999999", "9999-12-31T23:59:59.999999");
		}

		@Test
		void sameWorkDateInDifferentPostingsIsAllowed() throws Exception {
			Session employer = login(MemberRole.EMPLOYER);
			Map<String, Object> sameDay = schedule("2026-12-10", "2026-12-10T08:00:00", "2026-12-10T12:00:00", null, null);

			assertThat(employer.post(withOneSchedule(sameDay)).statusCode()).isEqualTo(201);
			assertThat(employer.post(withOneSchedule(sameDay)).statusCode()).isEqualTo(201);

			assertThat(count("SELECT COUNT(*) FROM work_schedule s JOIN job_posting p ON p.id = s.job_posting_id"
				+ " WHERE p.employer_id = ? AND s.work_date = '2026-12-10'", employer.id())).isEqualTo(2);
		}

		@Test
		void codesAndTextAtColumnLimitsAreStoredAsSent() throws Exception {
			// 공통 코드 목록이 미정이므로 허용 목록을 가정하지 않고, 길이 한도까지의 임의 문자열이 그대로 저장되는지만 본다.
			Session employer = login(MemberRole.EMPLOYER);
			Map<String, Object> body = valid();
			body.put("title", "가".repeat(200));
			body.put("cropCode", "C".repeat(40));
			body.put("workTypeCode", "W".repeat(40));
			body.put("workLocation", "나".repeat(500));
			body.put("description", "d".repeat(65535));

			HttpResponse<String> response = employer.post(body);

			assertThat(response.statusCode()).isEqualTo(201);
			Map<String, Object> posting = jdbc.queryForMap(
				"SELECT title, crop_code, work_type_code, work_location, LENGTH(description) description_bytes"
					+ " FROM job_posting WHERE id = ?", longAt(response, "$.jobPostingId"));
			assertThat(posting).containsEntry("title", body.get("title"))
				.containsEntry("crop_code", body.get("cropCode"))
				.containsEntry("work_type_code", body.get("workTypeCode"))
				.containsEntry("work_location", body.get("workLocation"));
			assertThat(((Number) posting.get("description_bytes")).intValue()).isEqualTo(65535);
		}
	}

	@Nested
	class Authorization {
		@Test
		void anonymousWithValidCsrfReturns401AndStoresNothing() throws Exception {
			FarmAuthClient anonymous = client();
			CsrfHeader csrf = anonymous.csrf();

			assertNothingStored(() -> assertThat(post(anonymous, csrf, valid()).statusCode()).isEqualTo(401));
		}

		@Test
		void workerWithValidCsrfReturns403EmployerRoleRequiredAndStoresNothing() throws Exception {
			Session worker = login(MemberRole.WORKER);

			assertNothingStored(() -> {
				HttpResponse<String> response = worker.post(valid());
				assertThat(response.statusCode()).isEqualTo(403);
				assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo("EMPLOYER_ROLE_REQUIRED");
			});
		}

		@Test
		void employerWithoutCsrfTokenReturns403AndStoresNothing() throws Exception {
			Session employer = login(MemberRole.EMPLOYER);

			assertNothingStored(() -> {
				HttpResponse<String> response = post(employer.client(), null, valid());
				assertThat(response.statusCode()).isEqualTo(403);
				assertThat(response.body()).doesNotContain("EMPLOYER_ROLE_REQUIRED");
			});
		}

		@Test
		void employerWithWrongCsrfTokenReturns403AndStoresNothing() throws Exception {
			Session employer = login(MemberRole.EMPLOYER);
			CsrfHeader wrong = new CsrfHeader(employer.csrf().name(), "not-the-session-token");

			assertNothingStored(() -> {
				HttpResponse<String> response = post(employer.client(), wrong, valid());
				assertThat(response.statusCode()).isEqualTo(403);
				assertThat(response.body()).doesNotContain("EMPLOYER_ROLE_REQUIRED");
			});
		}

		@Test
		void otherMethodOnJobPostingPathIsDeniedWithoutEmployerRoleMessage() throws Exception {
			// 조회 API는 아직 없다. 로그인한 고용주의 GET은 denyAll로 거부되고, 역할 안내 응답을 받지 않아야 한다.
			Session employer = login(MemberRole.EMPLOYER);

			HttpResponse<String> response = employer.client().get(PATH);

			assertThat(response.statusCode()).isEqualTo(403);
			assertThat(response.body()).doesNotContain("EMPLOYER_ROLE_REQUIRED");
		}

		@Test
		void ownerIsSessionMemberEvenWhenBodyNamesAnotherEmployer() throws Exception {
			Session employer = login(MemberRole.EMPLOYER);
			Member other = register(MemberRole.EMPLOYER);
			Map<String, Object> body = valid();
			body.put("employerId", other.getId());

			HttpResponse<String> response = employer.post(body);

			assertThat(response.statusCode()).isEqualTo(201);
			assertThat(longAt(response, "$.employerId")).isEqualTo(employer.id());
			assertThat(count("SELECT employer_id FROM job_posting WHERE id = ?", longAt(response, "$.jobPostingId")))
				.isEqualTo(employer.id());
			assertThat(count("SELECT COUNT(*) FROM job_posting WHERE employer_id = ?", other.getId())).isZero();
		}

		@Test
		void employerWhoseRoleChangedAfterLoginIsRejected() throws Exception {
			// 세션의 권한은 여전히 EMPLOYER지만, 등록 서비스가 현재 DB 역할을 다시 확인해 거부해야 한다.
			Session employer = login(MemberRole.EMPLOYER);
			jdbc.update("UPDATE member SET role = 'WORKER' WHERE id = ?", employer.id());

			assertNothingStored(() -> {
				HttpResponse<String> response = employer.post(valid());
				assertThat(response.statusCode()).isEqualTo(403);
				assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo("EMPLOYER_ROLE_REQUIRED");
			});
		}
	}

	@Nested
	class InputValidation {
		static Stream<Arguments> invalidFields() {
			return Stream.of(
				Arguments.of("title 공백", edit(b -> b.put("title", "   ")), "title"),
				Arguments.of("title 빈 값", edit(b -> b.put("title", "")), "title"),
				Arguments.of("title null", edit(b -> b.put("title", null)), "title"),
				Arguments.of("title 201자", edit(b -> b.put("title", "가".repeat(201))), "title"),
				Arguments.of("description 공백", edit(b -> b.put("description", " \t ")), "description"),
				Arguments.of("description 65536바이트", edit(b -> b.put("description", "d".repeat(65536))), "description"),
				Arguments.of("cropCode 누락", edit(b -> b.remove("cropCode")), "cropCode"),
				Arguments.of("cropCode 41자", edit(b -> b.put("cropCode", "C".repeat(41))), "cropCode"),
				Arguments.of("workTypeCode 빈 값", edit(b -> b.put("workTypeCode", "")), "workTypeCode"),
				Arguments.of("workTypeCode 41자", edit(b -> b.put("workTypeCode", "W".repeat(41))), "workTypeCode"),
				Arguments.of("workLocation null", edit(b -> b.put("workLocation", null)), "workLocation"),
				Arguments.of("workLocation 501자", edit(b -> b.put("workLocation", "나".repeat(501))), "workLocation"),
				Arguments.of("timeZone 누락", edit(b -> b.remove("timeZone")), "timeZone"),
				Arguments.of("timeZone 65자", edit(b -> b.put("timeZone", "A".repeat(65))), "timeZone"),
				Arguments.of("headcount null", edit(b -> b.put("headcount", null)), "headcount"),
				Arguments.of("headcount 0", edit(b -> b.put("headcount", 0)), "headcount"),
				Arguments.of("headcount 음수", edit(b -> b.put("headcount", -1)), "headcount"),
				Arguments.of("dailyWage null", edit(b -> b.put("dailyWage", null)), "dailyWage"),
				Arguments.of("dailyWage 0", edit(b -> b.put("dailyWage", 0)), "dailyWage"),
				Arguments.of("dailyWage 음수", edit(b -> b.put("dailyWage", -120000)), "dailyWage"),
				Arguments.of("schedules 누락", edit(b -> b.remove("schedules")), "schedules"),
				Arguments.of("schedules null", edit(b -> b.put("schedules", null)), "schedules"),
				Arguments.of("schedules 빈 배열", edit(b -> b.put("schedules", List.of())), "schedules"),
				Arguments.of("schedules null 원소", edit(b -> b.put("schedules", new ArrayList<>(java.util.Arrays.asList(
					firstSchedule(valid()), null)))), "schedules[1]"),
				Arguments.of("workDate 누락", edit(b -> firstSchedule(b).remove("workDate")), "schedules[0].workDate"),
				Arguments.of("plannedStartAt null", edit(b -> firstSchedule(b).put("plannedStartAt", null)), "schedules[0].plannedStartAt"),
				Arguments.of("plannedEndAt 누락", edit(b -> firstSchedule(b).remove("plannedEndAt")), "schedules[0].plannedEndAt"));
		}

		@ParameterizedTest(name = "{0}")
		@MethodSource("invalidFields")
		void invalidFieldReturns400WithFieldNameAndStoresNothing(String name, Map<String, Object> body, String field)
				throws Exception {
			Session employer = login(MemberRole.EMPLOYER);

			assertNothingStored(() -> assertError(employer.post(body), "INVALID_JOB_POSTING", field));
		}

		static Stream<Arguments> unreadableBodies() {
			String valid = json(valid());
			return Stream.of(
				Arguments.of("JSON 문법 오류", valid.substring(0, valid.length() - 1)),
				Arguments.of("빈 본문", ""),
				Arguments.of("배열 본문", "[]"),
				Arguments.of("없는 날짜", json(edit(b -> firstSchedule(b).put("workDate", "2026-02-30")))),
				Arguments.of("날짜 형식", json(edit(b -> firstSchedule(b).put("workDate", "2026/11/30")))),
				Arguments.of("일시 공백 구분", json(edit(b -> firstSchedule(b).put("plannedStartAt", "2026-11-30 08:00:00")))),
				Arguments.of("일시에 +09:00 오프셋", json(edit(b -> firstSchedule(b).put("plannedStartAt", "2026-11-30T08:00:00+09:00")))),
				Arguments.of("headcount 문자", json(edit(b -> b.put("headcount", "two")))),
				Arguments.of("headcount int 범위 초과", json(edit(b -> b.put("headcount", 2147483648L)))),
				Arguments.of("dailyWage 문자", json(edit(b -> b.put("dailyWage", "많이")))),
				Arguments.of("schedules 객체", json(edit(b -> b.put("schedules", Map.of())))));
		}

		@ParameterizedTest(name = "{0}")
		@MethodSource("unreadableBodies")
		void unreadableBodyReturns400InvalidRequestBodyAndStoresNothing(String name, String body) throws Exception {
			Session employer = login(MemberRole.EMPLOYER);

			assertNothingStored(() -> assertError(employer.postRaw(body), "INVALID_REQUEST_BODY", null));
		}
	}

	@Nested
	class TimeRules {
		static Stream<Arguments> invalidSchedules() {
			return Stream.of(
				Arguments.of("같은 요청의 중복 근무일", edit(b -> b.put("schedules", new ArrayList<>(List.of(
						schedule("2026-12-11", "2026-12-11T08:00:00", "2026-12-11T12:00:00", null, null),
						schedule("2026-12-11", "2026-12-11T13:00:00", "2026-12-11T17:00:00", null, null))))),
					"DUPLICATE_WORK_DATE", "schedules[1].workDate"),
				Arguments.of("종료 = 시작", one("2026-12-11", "2026-12-11T08:00:00", "2026-12-11T08:00:00", null, null),
					"INVALID_WORK_TIME", "schedules[0].plannedEndAt"),
				Arguments.of("종료 < 시작", one("2026-12-11", "2026-12-11T08:00:00", "2026-12-11T07:59:59", null, null),
					"INVALID_WORK_TIME", "schedules[0].plannedEndAt"),
				Arguments.of("근무일 ≠ 시작 일자", one("2026-12-12", "2026-12-11T08:00:00", "2026-12-11T12:00:00", null, null),
					"INVALID_WORK_TIME", "schedules[0].workDate"),
				Arguments.of("존재하지 않는 시간대", edit(b -> b.put("timeZone", "Mars/Olympus")), "INVALID_TIME_ZONE", "timeZone"),
				Arguments.of("시간대 대소문자 오류", edit(b -> b.put("timeZone", "asia/seoul")), "INVALID_TIME_ZONE", "timeZone"),
				Arguments.of("휴게 시작만 null", one("2026-12-11", "2026-12-11T08:00:00", "2026-12-11T16:00:00", null, "2026-12-11T13:00:00"),
					"INVALID_BREAK_TIME", "schedules[0].breakStartAt"),
				Arguments.of("휴게 종료 = 시작", one("2026-12-11", "2026-12-11T08:00:00", "2026-12-11T16:00:00", "2026-12-11T12:00:00", "2026-12-11T12:00:00"),
					"INVALID_BREAK_TIME", "schedules[0].breakEndAt"),
				Arguments.of("휴게 종료 < 시작", one("2026-12-11", "2026-12-11T08:00:00", "2026-12-11T16:00:00", "2026-12-11T13:00:00", "2026-12-11T12:00:00"),
					"INVALID_BREAK_TIME", "schedules[0].breakEndAt"),
				Arguments.of("휴게 종료가 근무 종료 이후", one("2026-12-11", "2026-12-11T08:00:00", "2026-12-11T16:00:00", "2026-12-11T15:30:00", "2026-12-11T16:00:01"),
					"INVALID_BREAK_TIME", "schedules[0].breakEndAt"),
				Arguments.of("DB 최소 연도 미만", one("0999-12-31", "0999-12-31T08:00:00", "0999-12-31T12:00:00", null, null),
					"INVALID_LOCAL_TIME", "schedules[0].plannedStartAt"),
				Arguments.of("마이크로초 초과 정밀도", one("2026-12-11", "2026-12-11T08:00:00.1234567", "2026-12-11T12:00:00", null, null),
					"INVALID_LOCAL_TIME", "schedules[0].plannedStartAt"),
				Arguments.of("휴게 일시 정밀도 초과", one("2026-12-11", "2026-12-11T08:00:00", "2026-12-11T16:00:00", "2026-12-11T12:00:00.0000001", "2026-12-11T13:00:00"),
					"INVALID_LOCAL_TIME", "schedules[0].breakStartAt"),
				// 미국 동부: 2026-03-08 02:00~03:00은 존재하지 않고, 2026-11-01 01:00~02:00은 두 번 존재한다.
				Arguments.of("DST로 존재하지 않는 시작 일시", zone("America/New_York",
						"2026-03-08", "2026-03-08T02:30:00", "2026-03-08T10:00:00"),
					"INVALID_LOCAL_TIME", "schedules[0].plannedStartAt"),
				Arguments.of("DST로 두 번 존재하는 종료 일시", zone("America/New_York",
						"2026-10-31", "2026-10-31T22:00:00", "2026-11-01T01:30:00"),
					"INVALID_LOCAL_TIME", "schedules[0].plannedEndAt"));
		}

		@ParameterizedTest(name = "{0}")
		@MethodSource("invalidSchedules")
		void invalidScheduleReturns400WithCodeAndFieldAndStoresNothing(String name, Map<String, Object> body,
				String code, String field) throws Exception {
			Session employer = login(MemberRole.EMPLOYER);

			assertNothingStored(() -> assertError(employer.post(body), code, field));
		}

		@Test
		void validDstZoneScheduleOutsideTransitionIsAccepted() throws Exception {
			Session employer = login(MemberRole.EMPLOYER);

			assertThat(employer.post(zone("America/New_York", "2026-03-08", "2026-03-08T03:00:00", "2026-03-08T11:00:00"))
				.statusCode()).isEqualTo(201);
		}

		private static Map<String, Object> one(String date, String start, String end, String breakStart, String breakEnd) {
			return withOneSchedule(schedule(date, start, end, breakStart, breakEnd));
		}

		private static Map<String, Object> zone(String zone, String date, String start, String end) {
			Map<String, Object> body = one(date, start, end, null, null);
			body.put("timeZone", zone);
			return body;
		}
	}

	@Nested
	class TransactionAndConstraints {
		@Test
		void oneInvalidScheduleAmongManyStoresNoPostingAndNoSchedule() throws Exception {
			Session employer = login(MemberRole.EMPLOYER);
			Map<String, Object> body = valid();
			body.put("schedules", new ArrayList<>(List.of(
				schedule("2026-12-13", "2026-12-13T08:00:00", "2026-12-13T12:00:00", null, null),
				schedule("2026-12-14", "2026-12-14T08:00:00", "2026-12-14T12:00:00", null, null),
				schedule("2026-12-15", "2026-12-15T08:00:00", "2026-12-15T07:00:00", null, null))));

			assertNothingStored(() -> assertError(employer.post(body), "INVALID_WORK_TIME", "schedules[2].plannedEndAt"));
		}

		@Test
		void databaseUniqueConstraintRejectsSameWorkDateInSamePosting() {
			Member employer = register(MemberRole.EMPLOYER);
			jdbc.update("INSERT INTO job_posting (employer_id, title, description, crop_code, work_type_code, work_location,"
				+ " default_headcount, default_daily_wage, time_zone) VALUES (?, 't', 'd', 'C', 'W', 'L', 1, 1, 'Asia/Seoul')",
				employer.getId());
			long postingId = count("SELECT MAX(id) FROM job_posting WHERE employer_id = ?", employer.getId());
			String insert = "INSERT INTO work_schedule (job_posting_id, work_date, planned_start_at, planned_end_at, status)"
				+ " VALUES (?, '2026-12-16', '2026-12-16 08:00:00', '2026-12-16 12:00:00', 'OPEN')";
			jdbc.update(insert, postingId);

			assertThatThrownBy(() -> jdbc.update(insert, postingId))
				.isInstanceOf(DuplicateKeyException.class)
				.hasMessageContaining("uq_posting_work_date");
		}

		@Test
		void legacyPostingWithoutTimeZoneIsReadableButApiRequiresTimeZone() throws Exception {
			// V4 이전 공고는 time_zone이 NULL일 수 있다. DB는 허용하고, 신규 API 요청은 필수로 검증한다.
			Member employer = register(MemberRole.EMPLOYER);
			jdbc.update("INSERT INTO job_posting (employer_id, title, description, crop_code, work_type_code, work_location,"
				+ " default_headcount, default_daily_wage) VALUES (?, 'legacy', 'd', 'C', 'W', 'L', 1, 1)", employer.getId());
			long legacyId = count("SELECT id FROM job_posting WHERE employer_id = ?", employer.getId());
			assertThat(jdbc.queryForObject("SELECT time_zone FROM job_posting WHERE id = ?", String.class, legacyId)).isNull();
			assertThat(postings.findById(legacyId)).hasValueSatisfying(posting -> {
				assertThat(posting.getTitle()).isEqualTo("legacy");
				assertThat(posting.getTimeZone()).isNull();
			});

			Session session = login(MemberRole.EMPLOYER);
			Map<String, Object> body = valid();
			body.remove("timeZone");
			assertNothingStored(() -> assertError(session.post(body), "INVALID_JOB_POSTING", "timeZone"));
		}
	}

	// ---- helpers ----

	private Member register(MemberRole role) {
		return members.save(new Member("job-test-" + UUID.randomUUID(), encoder.encode(PASSWORD), "공고 테스트 계정", role));
	}

	private Session login(MemberRole role) throws Exception {
		Member member = register(role);
		FarmAuthClient client = client();
		assertThat(client.login(member.getLoginId(), PASSWORD, client.csrf()).statusCode()).isEqualTo(204);
		// 로그인하면 CSRF 토큰이 새로 발급되므로 다시 받는다.
		return new Session(client, member.getId(), client.csrf());
	}

	private FarmAuthClient client() {
		FarmAuthClient client = new FarmAuthClient(port);
		clients.add(client);
		return client;
	}

	private static HttpResponse<String> post(FarmAuthClient client, CsrfHeader csrf, Object body) throws Exception {
		return client.post(PATH, "application/json", json(body), csrf);
	}

	private void assertNothingStored(ThrowingRunnable action) throws Exception {
		long postings = count("SELECT COUNT(*) FROM job_posting");
		long schedules = count("SELECT COUNT(*) FROM work_schedule");
		action.run();
		assertThat(count("SELECT COUNT(*) FROM job_posting")).as("job_posting rows").isEqualTo(postings);
		assertThat(count("SELECT COUNT(*) FROM work_schedule")).as("work_schedule rows").isEqualTo(schedules);
	}

	static void assertError(HttpResponse<String> response, String code, String field) {
		assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
		assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo(code);
		assertThat(JsonPath.<String>read(response.body(), "$.field")).isEqualTo(field);
	}

	private long count(String sql, Object... args) {
		Long value = jdbc.queryForObject(sql, Long.class, args);
		return value == null ? 0 : value;
	}

	private List<Map<String, Object>> scheduleTimes(long postingId) {
		return jdbc.queryForList("SELECT DATE_FORMAT(work_date, '%Y-%m-%d') workDate,"
			+ " DATE_FORMAT(planned_start_at, '%Y-%m-%dT%H:%i:%s.%f') plannedStartAt,"
			+ " DATE_FORMAT(planned_end_at, '%Y-%m-%dT%H:%i:%s.%f') plannedEndAt,"
			+ " DATE_FORMAT(break_start_at, '%Y-%m-%dT%H:%i:%s.%f') breakStartAt,"
			+ " DATE_FORMAT(break_end_at, '%Y-%m-%dT%H:%i:%s.%f') breakEndAt"
			+ " FROM work_schedule WHERE job_posting_id = ? ORDER BY work_date", postingId);
	}

	private static String dbTime(Object requestValue) {
		return requestValue == null ? null : LocalDateTime.parse((String) requestValue).format(DB_TIME);
	}

	static long longAt(HttpResponse<String> response, String path) {
		assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
		return ((Number) JsonPath.read(response.body(), path)).longValue();
	}

	static Map<String, Object> edit(Consumer<Map<String, Object>> change) {
		Map<String, Object> body = valid();
		change.accept(body);
		return body;
	}

	@FunctionalInterface
	interface ThrowingRunnable {
		void run() throws Exception;
	}

	record Session(FarmAuthClient client, long id, CsrfHeader csrf) {
		HttpResponse<String> post(Object body) throws Exception {
			return JobPostingHttpTests.post(client, csrf, body);
		}

		HttpResponse<String> postRaw(String body) throws Exception {
			return client.post(PATH, "application/json", body, csrf);
		}
	}
}
