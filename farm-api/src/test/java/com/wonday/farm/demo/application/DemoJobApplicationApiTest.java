package com.wonday.farm.demo.application;

import static com.wonday.farm.application.testsupport.ApplicationTestData.WORKER_ID;
import static com.wonday.farm.application.testsupport.ApplicationTestData.member;
import static com.wonday.farm.demo.application.DemoJobApplicationServiceTest.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import com.wonday.farm.application.repository.ApplicationRequestLockRepository;
import com.wonday.farm.auth.FarmPrincipal;
import com.wonday.farm.job.repository.JobPostingRepository;
import com.wonday.farm.job.repository.WorkScheduleRepository;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// standalone MockMvc: 실제 DemoJobApplicationApi + 실제 DemoJobApplicationService + 실제 오류 처리기, Repository는 mock.
// 실제 farm-demo 프로필 빈 등록·기본 프로필 차단, SecurityConfig의 hasRole("WORKER")·CSRF·세션, INSERT 실행은 검증하지 않는다.
// 프로필·SQL·보안 설정은 아래에서 선언(어노테이션·문자열)만 확인한다.
@DisplayName("farm-demo APP-02 이력·시연 준비 API (standalone MockMvc)")
class DemoJobApplicationApiTest {
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String SECRET = "jdbc:mysql://10.0.0.5/farm_db password=s3cr3t";

	private final MemberRepository members = mock(MemberRepository.class);
	private final JobPostingRepository postings = mock(JobPostingRepository.class);
	private final DemoJobApplicationRepository repository = mock(DemoJobApplicationRepository.class);
	private final ApplicationRequestLockRepository locks = mock(ApplicationRequestLockRepository.class);
	private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new DemoJobApplicationApi(new DemoJobApplicationService(members,
			postings, mock(WorkScheduleRepository.class), repository, locks, Clock.fixed(Instant.parse("2026-12-01T00:00:00Z"), ZoneOffset.UTC))))
		.setControllerAdvice(new DemoJobApplicationErrorHandler())
		.setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();

	@BeforeEach
	void signedInWorker() {
		FarmPrincipal principal = mock(FarmPrincipal.class);
		given(principal.memberId()).willReturn(WORKER_ID);
		SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
		given(members.findById(WORKER_ID)).willReturn(Optional.of(member(WORKER_ID, MemberRole.WORKER)));
		given(postings.existsById(101L)).willReturn(true);
		given(locks.lockWorker(WORKER_ID)).willReturn(Optional.of(org.springframework.beans.BeanUtils.instantiateClass(
			com.wonday.farm.application.entity.ApplicationRequestLock.class)));
	}

	@AfterEach
	void clear() { SecurityContextHolder.clearContext(); }

	@Nested
	@DisplayName("응답")
	class Responses {
		@Test
		@DisplayName("화면 GET 200 text/html, no-store")
		void page() throws Exception {
			var response = perform(get("/demo/job-apply"));
			assertThat(response.getStatus()).isEqualTo(200);
			assertThat(response.getContentType()).startsWith("text/html");
			assertNoStore(response);
		}

		@Test
		@DisplayName("이력: 쿼리 workerId=99는 무시하고 세션 근로자로 조회, current boolean·버전 null·확정/취소 연결 필드")
		void history() throws Exception {
			given(repository.findOwnHistory(WORKER_ID, 101L)).willReturn(List.of(row(9003, 203, 1, "NOT_SELECTED", 0, null, 77L, "CANCELLED")));

			var response = perform(get("/demo/api/applications/postings/101/history").param("workerId", "99"));

			assertThat(response.getStatus()).isEqualTo(200);
			assertNoStore(response);
			verify(repository).findOwnHistory(WORKER_ID, 101L);
			JsonNode item = parse(response).get("applications").get(0);
			assertThat(item.propertyNames()).containsExactlyInAnyOrder("applicationId", "workScheduleId", "applicationRound", "status",
				"current", "appliedTermsVersion", "assignmentId", "changeType");
			assertThat(item.get("current").isBoolean()).isTrue();
			assertThat(item.get("current").asBoolean()).isFalse();
			assertThat(item.get("appliedTermsVersion").isNull()).isTrue();
		}

		@Test
		@DisplayName("시연 준비: 새로 만들면 201, 기존 공고면 200, 둘 다 no-store")
		void fixture() throws Exception {
			given(members.findByLoginId("demo-employer")).willReturn(Optional.empty());
			assertError(perform(post("/demo/api/applications/fixtures")), 409, "DEMO_EMPLOYER_UNAVAILABLE");

			given(members.findByLoginId("demo-employer")).willReturn(Optional.of(member(10L, MemberRole.EMPLOYER)));
			given(locks.lockWorker(WORKER_ID)).willReturn(Optional.of(org.springframework.beans.BeanUtils.instantiateClass(
				com.wonday.farm.application.entity.ApplicationRequestLock.class)));
			given(repository.findFixturePosting(org.mockito.ArgumentMatchers.eq(10L), anyString())).willReturn(Optional.of(444L));
			var existing = perform(post("/demo/api/applications/fixtures"));
			assertThat(existing.getStatus()).isEqualTo(200);
			assertNoStore(existing);
			assertThat(parse(existing).get("jobPostingId").asLong()).isEqualTo(444L);
			assertThat(parse(existing).get("created").asBoolean()).isFalse();
		}
	}

	@Nested
	@DisplayName("오류")
	class Errors {
		@Test
		@DisplayName("고용주 세션은 이력·준비 모두 403 WORKER_ROLE_REQUIRED이고 저장소 호출 없음")
		void employerDenied() throws Exception {
			given(members.findById(WORKER_ID)).willReturn(Optional.of(member(WORKER_ID, MemberRole.EMPLOYER)));

			assertError(perform(get("/demo/api/applications/postings/101/history")), 403, "WORKER_ROLE_REQUIRED");
			assertError(perform(post("/demo/api/applications/fixtures")), 403, "WORKER_ROLE_REQUIRED");
			verifyNoInteractions(repository, locks);
		}

		@Test
		@DisplayName("principal 없음(standalone)은 403, 회원 조회 없음 — 실제 체인의 익명 401과는 별개")
		void noPrincipal() throws Exception {
			SecurityContextHolder.clearContext();
			assertError(perform(get("/demo/api/applications/postings/101/history")), 403, "WORKER_ROLE_REQUIRED");
			verifyNoInteractions(members);
		}

		@ParameterizedTest(name = "id={0}")
		@ValueSource(strings = {"0", "-1", "abc", "9223372036854775808"})
		@DisplayName("잘못된 공고 ID는 400 INVALID_REQUEST")
		void invalidId(String id) throws Exception {
			assertError(perform(get("/demo/api/applications/postings/" + id + "/history")), 400, "INVALID_REQUEST");
			verifyNoInteractions(repository);
		}

		@Test
		@DisplayName("없는 공고 404, DB 오류 503, 준비 충돌 409, 그 밖의 예외 500이고 예외 메시지 미노출")
		void mapped() throws Exception {
			given(postings.existsById(102L)).willReturn(false);
			assertError(perform(get("/demo/api/applications/postings/102/history")), 404, "JOB_POSTING_NOT_FOUND");

			given(repository.findOwnHistory(anyLong(), anyLong())).willThrow(new DataAccessResourceFailureException(SECRET),
				new IllegalStateException(SECRET));
			assertError(perform(get("/demo/api/applications/postings/101/history")), 503, "SERVICE_UNAVAILABLE");
			assertError(perform(get("/demo/api/applications/postings/101/history")), 500, "INTERNAL_SERVER_ERROR");

			given(members.findByLoginId("demo-employer")).willThrow(new DataIntegrityViolationException(SECRET));
			assertError(perform(post("/demo/api/applications/fixtures")), 409, "DEMO_FIXTURE_CONFLICT");
		}
	}

	@Nested
	@DisplayName("선언 확인 (실행 검증 아님)")
	class Declarations {
		@Test
		@DisplayName("API·서비스·저장소·오류 처리기 모두 @Profile(\"farm-demo\")")
		void profiles() {
			for (Class<?> type : List.<Class<?>>of(DemoJobApplicationApi.class, DemoJobApplicationService.class,
					DemoJobApplicationRepository.class, DemoJobApplicationErrorHandler.class)) {
				assertThat(type.getAnnotation(Profile.class).value()).as(type.getSimpleName()).containsExactly("farm-demo");
			}
		}

		@Test
		@DisplayName("저장소 SQL: 이력은 worker_id·공고로 제한·id desc·limit 500, 변경 쿼리는 INSERT 하나뿐(UPDATE·DELETE 없음)")
		void sql() throws Exception {
			String history = query("findOwnHistory", long.class, long.class);
			assertThat(history).contains("where app.worker_id = :workerId and schedule.job_posting_id = :postingId",
				"order by app.id desc", "limit 500").doesNotContain("phone", "password", "identity");
			String insert = query("insertNotSelectedFixture", long.class, long.class, java.time.LocalDateTime.class);
			assertThat(insert).startsWith("insert into application").contains("'NOT_SELECTED'", "1, 1, 1)");
			for (var method : DemoJobApplicationRepository.class.getDeclaredMethods()) {
				var annotation = method.getAnnotation(Query.class);
				if (annotation == null) continue;
				assertThat(annotation.value().toLowerCase()).as(method.getName()).doesNotContain("update ", "delete ");
				if (method.isAnnotationPresent(Modifying.class)) assertThat(method.getName()).isEqualTo("insertNotSelectedFixture");
			}
		}

		@Test
		@DisplayName("SecurityConfig: farm-demo 분기에 화면 permitAll·이력 GET/준비 POST hasRole(WORKER), 지원 POST는 분기 밖 hasRole(WORKER), CSRF 예외 없음")
		void securityConfig() throws Exception {
			String config = Files.readString(Path.of("src/main/java/com/wonday/farm/auth/SecurityConfig.java")).replaceAll("\\s+", " ");
			int demo = config.indexOf("if (environment.matchesProfiles(\"farm-demo\")) {");
			String block = config.substring(demo, config.indexOf("}", demo));
			assertThat(block).contains("auth.requestMatchers(HttpMethod.GET, \"/demo/job-apply\").permitAll();",
				"auth.requestMatchers(HttpMethod.GET, \"/demo/api/applications/postings/*/history\").hasRole(\"WORKER\");",
				"auth.requestMatchers(HttpMethod.POST, \"/demo/api/applications/fixtures\").hasRole(\"WORKER\");")
				.doesNotContain("/api/job-postings/*/applications");
			assertThat(config.substring(config.indexOf("}", demo)))
				.contains(".requestMatchers(HttpMethod.POST, \"/api/job-postings/*/applications\").hasRole(\"WORKER\")");
			assertThat(config).doesNotContain("ignoringRequestMatchers", ".csrf(");
		}

		private String query(String name, Class<?>... types) throws Exception {
			return DemoJobApplicationRepository.class.getMethod(name, types).getAnnotation(Query.class).value().replaceAll("\\s+", " ").trim();
		}
	}

	private MockHttpServletResponse perform(RequestBuilder request) throws Exception {
		return mvc.perform(request).andReturn().getResponse();
	}

	private static JsonNode parse(MockHttpServletResponse response) throws Exception {
		return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
	}

	private static void assertNoStore(MockHttpServletResponse response) {
		assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
	}

	private static void assertError(MockHttpServletResponse response, int status, String code) throws Exception {
		assertThat(response.getStatus()).isEqualTo(status);
		assertNoStore(response);
		JsonNode body = parse(response);
		assertThat(body.propertyNames()).containsExactly("code", "message");
		assertThat(body.get("code").asString()).isEqualTo(code);
		assertThat(response.getContentAsString(StandardCharsets.UTF_8)).doesNotContain("jdbc", "s3cr3t", "Exception");
	}
}
