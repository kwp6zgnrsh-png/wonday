package com.wonday.farm.insurance.service;

import static com.wonday.farm.insurance.testsupport.InsuranceFixtures.assignment;
import static com.wonday.farm.insurance.testsupport.InsuranceFixtures.member;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import com.wonday.farm.auth.FarmPrincipal;
import com.wonday.farm.demo.assignment.DemoWorkAssignmentApi;
import com.wonday.farm.demo.assignment.DemoWorkAssignmentErrorHandler;
import com.wonday.farm.demo.assignment.DemoWorkAssignmentRepository;
import com.wonday.farm.insurance.api.InsuranceIntegrationApi;
import com.wonday.farm.insurance.dto.WorkAssignmentResponse;
import com.wonday.farm.insurance.exception.InsuranceApiException;
import com.wonday.farm.insurance.exception.InsuranceErrorHandler;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import com.wonday.farm.work.entity.WorkAssignment;
import com.wonday.farm.work.repository.WorkAssignmentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// 보험 확정 근무 상세와 근로자 demo 상세의 completedAt. 실제 WorkAssignmentQueryService에 Repository mock을 연결하고,
// JSON은 standalone MockMvc(실제 컨트롤러·오류 처리기)로 확인한다. 서버 토큰 필터·근로자 세션·findOwned SQL 필터·
// Boot JsonMapper 설정은 없다 (통합 검증 대상).
@DisplayName("보험·근로자 demo 확정 근무 상세의 완료 일시 (Repository mock)")
class WorkAssignmentCompletionResponseTest {
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final long WORKER_ID = 5L;
	private static final long ASSIGNMENT_ID = 31L;

	private final MemberRepository members = mock(MemberRepository.class);
	private final WorkAssignmentRepository assignments = mock(WorkAssignmentRepository.class);
	private final WorkAssignmentQueryService service = new WorkAssignmentQueryService(new InsuranceDataAccess(members), assignments);
	private WorkAssignment stored;

	@BeforeEach
	void storedAssignment() {
		given(members.findById(WORKER_ID)).willReturn(Optional.of(member(WORKER_ID, MemberRole.WORKER)));
		stored = assignment(ASSIGNMENT_ID);
		given(assignments.findOwned(WORKER_ID, ASSIGNMENT_ID)).willReturn(Optional.of(stored));
	}

	@AfterEach
	void clear() {
		SecurityContextHolder.clearContext();
	}

	private void completed(String utc, Long by) {
		ReflectionTestUtils.setField(stored, "completedAtUtc", utc == null ? null : LocalDateTime.parse(utc));
		ReflectionTestUtils.setField(stored, "completedBy", by);
	}

	@Nested
	@DisplayName("서비스 매핑")
	class Mapping {
		@Test
		@DisplayName("완료된 근무: 저장된 UTC 완료 시각을 약정 시간대(+09:00)로 변환, 마이크로초 유지")
		void completedAtInAgreedZone() {
			completed("2026-11-30T08:30:00.000001", 10L);

			var response = service.find(WORKER_ID, ASSIGNMENT_ID);

			assertThat(response.completedAt()).isEqualTo(OffsetDateTime.parse("2026-11-30T17:30:00.000001+09:00"));
			assertThat(response.completedAt().getOffset().getTotalSeconds()).isEqualTo(9 * 3600);
		}

		@Test
		@DisplayName("미완료·기존 행(두 컬럼 NULL)은 completedAt null이고 다른 약정 응답은 그대로")
		void notCompletedIsNull() {
			var response = service.find(WORKER_ID, ASSIGNMENT_ID);

			assertThat(response.completedAt()).isNull();
			assertThat(response.agreedEndAt()).isEqualTo(OffsetDateTime.parse("2026-11-30T17:00+09:00"));
			assertThat(response.agreedBreakStartAt()).isEqualTo(OffsetDateTime.parse("2026-11-30T12:00+09:00"));
			assertThat(response.agreedDailyWage()).isEqualTo(120000L);
			assertThat(response.workerId()).isEqualTo(WORKER_ID);
		}

		@Test
		@DisplayName("응답 레코드에 completedBy 성분이 없다")
		void noCompletedByComponent() {
			assertThat(Arrays.stream(WorkAssignmentResponse.class.getRecordComponents()).map(c -> c.getName()))
				.doesNotContain("completedBy").endsWith("completedAt");
		}

		@Test
		@DisplayName("좌표·휴게가 있는 완료 근무도 좌표·휴게를 그대로 반환")
		void coordinatesAndBreakKept() {
			ReflectionTestUtils.setField(stored, "agreedLatitude", new BigDecimal("37.123457"));
			ReflectionTestUtils.setField(stored, "agreedLongitude", new BigDecimal("127.987654"));
			completed("2026-11-30T09:00", 10L);

			var response = service.find(WORKER_ID, ASSIGNMENT_ID);

			assertThat(response.agreedLatitude()).isEqualByComparingTo("37.123457");
			assertThat(response.agreedLongitude()).isEqualByComparingTo("127.987654");
			assertThat(response.agreedBreakEndAt()).isEqualTo(OffsetDateTime.parse("2026-11-30T13:00+09:00"));
			assertThat(response.completedAt()).isEqualTo(OffsetDateTime.parse("2026-11-30T18:00+09:00"));
		}

		@Test
		@DisplayName("완료된 근무라도 필수 약정 누락·휴게 반쪽·좌표 반쪽·해석 불가 시간대는 기존대로 503")
		void existingMissingInfoHandlingKept() {
			completed("2026-11-30T09:00", 10L);
			for (var broken : List.<Runnable>of(
					() -> ReflectionTestUtils.setField(stored, "agreedWorkDate", null),
					() -> ReflectionTestUtils.setField(stored, "agreedBreakEndAt", null),
					() -> ReflectionTestUtils.setField(stored, "agreedLatitude", new BigDecimal("37.1")),
					() -> ReflectionTestUtils.setField(stored, "agreedTimeZone", "Seoul"))) {
				storedAssignment();
				completed("2026-11-30T09:00", 10L);
				broken.run();
				assertThatThrownBy(() -> service.find(WORKER_ID, ASSIGNMENT_ID)).isInstanceOfSatisfying(InsuranceApiException.class,
					error -> assertThat(error.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
			}
		}

		@Test
		@DisplayName("소유 근무 조회(findOwned)만 호출하고 완료 시각을 현재 공고·다른 저장소에서 보완하지 않는다")
		void onlyOwnedLookup() {
			service.find(WORKER_ID, ASSIGNMENT_ID);

			verify(assignments).findOwned(WORKER_ID, ASSIGNMENT_ID);
			verifyNoMoreInteractions(assignments);
		}

		@Test
		@DisplayName("[특성화] 조회 서비스는 반쪽 완료 상태를 따로 검사하지 않는다: 처리자만 있으면 null, 시각만 있으면 그 시각")
		void halfStateIsNotCheckedOnRead() {
			completed(null, 10L);
			assertThat(service.find(WORKER_ID, ASSIGNMENT_ID).completedAt()).isNull();

			completed("2026-11-30T09:00", null);
			assertThat(service.find(WORKER_ID, ASSIGNMENT_ID).completedAt()).isEqualTo(OffsetDateTime.parse("2026-11-30T18:00+09:00"));
		}
	}

	@Nested
	@DisplayName("JSON 응답 (standalone MockMvc)")
	class Json {
		private final MockMvc insurance = MockMvcBuilders
			.standaloneSetup(new InsuranceIntegrationApi(mock(WorkerVerificationService.class), service, mock(WorkChangeQueryService.class)))
			.setControllerAdvice(new InsuranceErrorHandler()).build();
		private final MockMvc demo = MockMvcBuilders
			.standaloneSetup(new DemoWorkAssignmentApi(members, mock(DemoWorkAssignmentRepository.class), service))
			.setControllerAdvice(new DemoWorkAssignmentErrorHandler())
			.setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();

		@BeforeEach
		void signedInWorker() {
			FarmPrincipal principal = mock(FarmPrincipal.class);
			given(principal.memberId()).willReturn(WORKER_ID);
			SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
		}

		@Test
		@DisplayName("미완료: 보험·demo 모두 completedAt 키가 있고 값은 JSON null, completedBy 키 없음")
		void explicitNull() throws Exception {
			for (JsonNode body : List.of(insuranceBody(), demoBody())) {
				assertThat(body.has("completedAt")).isTrue();
				assertThat(body.get("completedAt").isNull()).isTrue();
				assertThat(body.has("completedBy")).isFalse();
			}
		}

		@Test
		@DisplayName("완료: 보험·demo 모두 \"2026-11-30T17:30:00.000001+09:00\"이고 처리자 ID(10)는 응답에 없음")
		void completedValue() throws Exception {
			completed("2026-11-30T08:30:00.000001", 10L);

			for (JsonNode body : List.of(insuranceBody(), demoBody())) {
				assertThat(body.get("completedAt").asString()).isEqualTo("2026-11-30T17:30:00.000001+09:00");
				assertThat(body.has("completedBy")).isFalse();
				assertThat(body.toString()).doesNotContain("completedBy");
			}
		}

		private JsonNode insuranceBody() throws Exception {
			return parse(insurance.perform(get("/api/internal/insurance/workers/5/work-assignments/31")).andReturn().getResponse());
		}

		private JsonNode demoBody() throws Exception {
			return parse(demo.perform(get("/demo/api/work-assignments/31")).andReturn().getResponse());
		}

		private JsonNode parse(MockHttpServletResponse response) throws Exception {
			assertThat(response.getStatus()).isEqualTo(200);
			return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
		}
	}
}
