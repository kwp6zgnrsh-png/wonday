package com.wonday.farm.work.api;

import static com.wonday.farm.application.testsupport.ApplicationTestData.member;
import static com.wonday.farm.work.testsupport.WorkApplicantRows.EMPLOYER;
import static com.wonday.farm.work.testsupport.WorkApplicantRows.POSTING;
import static com.wonday.farm.work.testsupport.WorkApplicantRows.SCHEDULE;
import static com.wonday.farm.work.testsupport.WorkApplicantRows.contact;
import static com.wonday.farm.work.testsupport.WorkApplicantRows.row;
import static com.wonday.farm.work.testsupport.WorkApplicantRows.schedule;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.wonday.farm.application.service.ApplicationConditionMapper;
import com.wonday.farm.application.testsupport.MyApplicationRows;
import com.wonday.farm.auth.FarmPrincipal;
import com.wonday.farm.job.repository.WorkScheduleRepository;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import com.wonday.farm.reference.AgriculturalCodeCatalog;
import com.wonday.farm.work.exception.WorkApplicantErrorHandler;
import com.wonday.farm.work.repository.WorkApplicantRepository;
import com.wonday.farm.work.service.WorkApplicantQueryService;
import com.wonday.farm.work.validation.WorkApplicantCursor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// standalone MockMvc: 실제 컨트롤러 + 실제 서비스·APP-04 매퍼 + 실제 오류 처리기, 저장소는 mock.
// Security 필터 체인(익명 401·hasRole·세션 쿠키·CSRF)과 Spring Boot JsonMapper 설정은 이 테스트 범위가 아니다.
@DisplayName("WORK-01 지원자 API (standalone MockMvc)")
class WorkApplicantApiTest {
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String SECRET = "jdbc:mysql://10.0.0.5/farm_db password=s3cr3t resident=900101-1234567";
	private static final String BASE = "/api/job-postings/101/schedules/201/applications";
	private final MemberRepository members = mock(MemberRepository.class);
	private final WorkApplicantRepository repository = mock(WorkApplicantRepository.class);
	private final WorkScheduleRepository schedules = mock(WorkScheduleRepository.class);
	private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new WorkApplicantApi(new WorkApplicantQueryService(members, repository,
			schedules, new ApplicationConditionMapper(new AgriculturalCodeCatalog()))))
		.setControllerAdvice(new WorkApplicantErrorHandler())
		.setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();

	@BeforeEach
	void signIn() {
		signInAs(EMPLOYER);
		given(members.findById(EMPLOYER)).willReturn(Optional.of(member(EMPLOYER, MemberRole.EMPLOYER)));
		given(repository.findOwnedPosting(EMPLOYER, POSTING)).willReturn(Optional.of(POSTING));
		given(repository.findOwnedSchedule(EMPLOYER, POSTING, SCHEDULE)).willReturn(Optional.of(schedule("PAUSED", 3)));
		given(repository.findCurrent(anyLong(), anyLong(), anyLong(), anyString(), any(), any(), anyInt())).willReturn(List.of());
		given(schedules.countOccupyingAssignments(SCHEDULE)).willReturn(1L);
	}

	@AfterEach
	void clear() { SecurityContextHolder.clearContext(); }

	private static void signInAs(long id) {
		FarmPrincipal principal = mock(FarmPrincipal.class);
		given(principal.memberId()).willReturn(id);
		SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
	}
	private MockHttpServletResponse call(RequestBuilder request) throws Exception { return mvc.perform(request).andReturn().getResponse(); }
	private static JsonNode body(MockHttpServletResponse response) throws Exception {
		return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
	}
	private void expectError(RequestBuilder request, int status, String code) throws Exception {
		var response = call(request);
		assertThat(response.getStatus()).isEqualTo(status);
		assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
		var json = body(response);
		assertThat(json.get("code").asString()).isEqualTo(code);
		assertThat(json.propertyNames()).containsExactlyInAnyOrder("code", "message");
		assertThat(response.getContentAsString(StandardCharsets.UTF_8)).doesNotContain("s3cr3t", "jdbc:", "900101", "Exception");
	}

	@Nested
	@DisplayName("정상 응답")
	class Success {
		@Test
		@DisplayName("목록 200·no-store: 빈 배열·null 커서·집계·모집 상태·날짜 JSON, 기본 PENDING·20")
		void emptyList() throws Exception {
			var response = call(get(BASE));
			assertThat(response.getStatus()).isEqualTo(200);
			assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
			var json = body(response);
			assertThat(json.get("jobPostingId").asLong()).isEqualTo(101);
			assertThat(json.get("workScheduleId").asLong()).isEqualTo(201);
			assertThat(json.get("workDate").asString()).isEqualTo("2026-12-10");
			assertThat(json.get("recruitmentStatus").asString()).isEqualTo("PAUSED");
			assertThat(json.get("headcount").asInt()).isEqualTo(3);
			assertThat(json.get("confirmedCount").asLong()).isEqualTo(1);
			assertThat(json.get("remainingSlots").asLong()).isEqualTo(2);
			assertThat(json.get("items").isArray()).isTrue();
			assertThat(json.get("items").size()).isZero();
			assertThat(json.get("nextCursor").isNull()).isTrue();
			assertThat(json.get("hasMore").asBoolean()).isFalse();
			verify(repository).findCurrent(EMPLOYER, POSTING, SCHEDULE, "PENDING", null, null, 21);
		}

		@Test
		@DisplayName("목록 항목 JSON: 마스킹 번호만(phone 필드 없음)·+09:00 마이크로초·null·버전·상태·다음 커서")
		void itemJson() throws Exception {
			given(repository.findCurrent(EMPLOYER, POSTING, SCHEDULE, "ALL", null, null, 2)).willReturn(List.of(
				row(v -> v.put("latestTermsVersion", 4)), row(v -> v.put("applicationId", 600L))));
			var json = body(call(get(BASE).param("status", "ALL").param("limit", "1")));
			var item = json.get("items").get(0);
			assertThat(item.propertyNames()).doesNotContain("phone").contains("maskedPhone", "phoneState", "canViewContact", "workStatus");
			assertThat(item.get("maskedPhone").asString()).isEqualTo("010-****-1001");
			assertThat(item.get("name").asString()).isEqualTo("시연 근로자 1");
			assertThat(item.get("appliedAt").asString()).isEqualTo("2026-12-01T15:00:00.123456+09:00");
			assertThat(item.get("legacyAppliedAt").isNull()).isTrue();
			assertThat(item.get("assignmentId").isNull()).isTrue();
			assertThat(item.get("appliedTermsVersion").asInt()).isEqualTo(3);
			assertThat(item.get("latestTermsVersion").asInt()).isEqualTo(4);
			assertThat(item.get("conditionComparisonStatus").asString()).isEqualTo("CHANGED");
			assertThat(item.get("status").asString()).isEqualTo("PENDING");
			assertThat(json.get("nextCursor").asString()).isEqualTo(WorkApplicantCursor.encode(EMPLOYER, POSTING, SCHEDULE, "ALL", 501));
			assertThat(json.get("hasMore").asBoolean()).isTrue();
		}

		@Test
		@DisplayName("상세 200: phone 필드 없음, 조건 두 묶음·changedFields·unavailableComparisonFields·comparisonComplete·assignment=null")
		void detailJson() throws Exception {
			given(repository.findCurrentOwned(EMPLOYER, POSTING, SCHEDULE, 501)).willReturn(Optional.of(row(v -> {
				v.put("appliedTermsVersion", 1); v.put("latestTermsVersion", 2); MyApplicationRows.revision(v);
				v.put("plannedStartAt", LocalDateTime.parse("2026-12-10T10:00")); })));
			var response = call(get(BASE + "/501"));
			assertThat(response.getStatus()).isEqualTo(200);
			assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
			var json = body(response);
			assertThat(json.propertyNames()).doesNotContain("phone");
			assertThat(json.get("changedFields").get(0).asString()).isEqualTo("plannedStartAt");
			assertThat(json.get("unavailableComparisonFields").size()).isEqualTo(3);
			assertThat(json.get("comparisonComplete").asBoolean()).isFalse();
			assertThat(json.get("appliedConditions").get("latitude").isNull()).isTrue();
			assertThat(json.get("latestConditions").get("plannedStartAt").asString()).isEqualTo("2026-12-10T10:00:00+09:00");
			assertThat(json.get("assignment").isNull()).isTrue();
			assertThat(json.get("canViewContact").asBoolean()).isTrue();
		}

		@Test
		@DisplayName("연락처 200·no-store: 정규화된 전체 번호와 상태만, 주민번호·식별정보 필드 없음")
		void contactJson() throws Exception {
			given(repository.findContact(EMPLOYER, POSTING, SCHEDULE, 501)).willReturn(Optional.of(contact(501, "SELECTED", 1, "010-0000-1001")));
			var response = call(get(BASE + "/501/contact"));
			assertThat(response.getStatus()).isEqualTo(200);
			assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
			var json = body(response);
			assertThat(json.propertyNames()).containsExactlyInAnyOrder("applicationId", "workerId", "name", "phone", "phoneState");
			assertThat(json.get("phone").asString()).isEqualTo("01000001001");
			assertThat(json.get("phoneState").asString()).isEqualTo("AVAILABLE");
		}

		@Test
		@DisplayName("미등록 번호 연락처: phone=null·NOT_REGISTERED")
		void contactNull() throws Exception {
			given(repository.findContact(EMPLOYER, POSTING, SCHEDULE, 501)).willReturn(Optional.of(contact(501, "PENDING", 1, null)));
			var json = body(call(get(BASE + "/501/contact")));
			assertThat(json.get("phone").isNull()).isTrue();
			assertThat(json.get("phoneState").asString()).isEqualTo("NOT_REGISTERED");
		}

		@Test
		@DisplayName("세션 principal의 회원 ID만 사용: employerId 쿼리 파라미터 무시")
		void principal() throws Exception {
			call(get(BASE).param("employerId", "99"));
			verify(repository).findOwnedPosting(EMPLOYER, POSTING);
			verify(repository, never()).findOwnedPosting(org.mockito.ArgumentMatchers.eq(99L), anyLong());
		}
	}

	@Nested
	@DisplayName("오류 응답")
	class Errors {
		@ParameterizedTest
		@ValueSource(strings = {"0", "-1", "01", "1.5", "abc", "9223372036854775808", "+1"})
		@DisplayName("공고·일정·지원 ID 형식 오류·범위 초과는 400 INVALID_REQUEST, 저장소 조회 없음")
		void badIds(String id) throws Exception {
			expectError(get("/api/job-postings/{p}/schedules/201/applications", id), 400, "INVALID_REQUEST");
			expectError(get("/api/job-postings/101/schedules/{s}/applications", id), 400, "INVALID_REQUEST");
			expectError(get(BASE + "/{a}", id), 400, "INVALID_REQUEST");
			expectError(get(BASE + "/{a}/contact", id), 400, "INVALID_REQUEST");
			verifyNoInteractions(repository);
		}

		@ParameterizedTest
		@ValueSource(strings = {"status", "limit", "cursor"})
		@DisplayName("status·limit·cursor 중복은 400 INVALID_REQUEST")
		void duplicates(String name) throws Exception {
			expectError(get(BASE).param(name, "PENDING", "PENDING"), 400, "INVALID_REQUEST");
		}

		@Test
		@DisplayName("잘못된 필터·limit은 INVALID_REQUEST, 잘못된·다른 범위 커서는 INVALID_CURSOR")
		void filterLimitCursor() throws Exception {
			expectError(get(BASE).param("status", "CONFIRMED"), 400, "INVALID_REQUEST");
			expectError(get(BASE).param("limit", "101"), 400, "INVALID_REQUEST");
			expectError(get(BASE).param("limit", ""), 400, "INVALID_REQUEST");
			expectError(get(BASE).param("cursor", "bad cursor"), 400, "INVALID_CURSOR");
			expectError(get(BASE).param("cursor", ""), 400, "INVALID_CURSOR");
			expectError(get(BASE).param("cursor", WorkApplicantCursor.encode(EMPLOYER, POSTING, 202, "PENDING", 1)), 400, "INVALID_CURSOR");
		}

		@Test
		@DisplayName("근로자·principal 없음은 403 EMPLOYER_ROLE_REQUIRED (실제 익명 401은 Security 체인 범위라 미확인)")
		void role() throws Exception {
			given(members.findById(EMPLOYER)).willReturn(Optional.of(member(EMPLOYER, MemberRole.WORKER)));
			expectError(get(BASE), 403, "EMPLOYER_ROLE_REQUIRED");
			expectError(get(BASE + "/501/contact"), 403, "EMPLOYER_ROLE_REQUIRED");
			SecurityContextHolder.clearContext();
			expectError(get(BASE + "/501"), 403, "EMPLOYER_ROLE_REQUIRED");
		}

		@Test
		@DisplayName("타인 공고 404 JOB_POSTING_NOT_FOUND, 다른 공고의 일정 404 WORK_SCHEDULE_NOT_FOUND, 없는·과거 지원 404 APPLICATION_NOT_FOUND")
		void notFound() throws Exception {
			given(repository.findOwnedPosting(EMPLOYER, 999)).willReturn(Optional.empty());
			expectError(get("/api/job-postings/999/schedules/201/applications"), 404, "JOB_POSTING_NOT_FOUND");
			given(repository.findOwnedSchedule(EMPLOYER, POSTING, 999)).willReturn(Optional.empty());
			expectError(get("/api/job-postings/101/schedules/999/applications/501/contact"), 404, "WORK_SCHEDULE_NOT_FOUND");
			given(repository.findCurrentOwned(EMPLOYER, POSTING, SCHEDULE, 401)).willReturn(Optional.empty());
			expectError(get(BASE + "/401"), 404, "APPLICATION_NOT_FOUND");
			given(repository.findContact(EMPLOYER, POSTING, SCHEDULE, 777)).willReturn(Optional.empty());
			expectError(get(BASE + "/777/contact"), 404, "APPLICATION_NOT_FOUND");
		}

		@Test
		@DisplayName("미선정·과거 회차 연락처는 403 CONTACT_ACCESS_DENIED, 번호 미노출")
		void contactDenied() throws Exception {
			given(repository.findContact(EMPLOYER, POSTING, SCHEDULE, 501)).willReturn(Optional.of(contact(501, "NOT_SELECTED", 1, "01000001001")));
			given(repository.findContact(EMPLOYER, POSTING, SCHEDULE, 401)).willReturn(Optional.of(contact(401, "PENDING", 0, "01000001002")));
			expectError(get(BASE + "/501/contact"), 403, "CONTACT_ACCESS_DENIED");
			expectError(get(BASE + "/401/contact"), 403, "CONTACT_ACCESS_DENIED");
			var text = call(get(BASE + "/401/contact")).getContentAsString(StandardCharsets.UTF_8);
			assertThat(text).doesNotContain("0100000100");
		}

		@Test
		@DisplayName("DB 접근·쿼리 시간 초과·트랜잭션 생성 실패·시간 초과는 503 SERVICE_UNAVAILABLE, 내부 정보 미노출")
		void unavailable() throws Exception {
			given(repository.findCurrent(anyLong(), anyLong(), anyLong(), anyString(), any(), any(), anyInt()))
				.willThrow(new DataAccessResourceFailureException(SECRET), new QueryTimeoutException(SECRET),
					new CannotCreateTransactionException(SECRET), new TransactionTimedOutException(SECRET));
			for (int i = 0; i < 4; i++) expectError(get(BASE), 503, "SERVICE_UNAVAILABLE");
		}

		@Test
		@DisplayName("그 밖의 예외는 500 INTERNAL_SERVER_ERROR, 메시지·스택 미노출")
		void unexpected() throws Exception {
			given(repository.findContact(EMPLOYER, POSTING, SCHEDULE, 501)).willThrow(new IllegalStateException(SECRET));
			expectError(get(BASE + "/501/contact"), 500, "INTERNAL_SERVER_ERROR");
		}
	}
}
