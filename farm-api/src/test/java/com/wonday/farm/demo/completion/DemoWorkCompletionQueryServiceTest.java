package com.wonday.farm.demo.completion;

import static com.wonday.farm.insurance.testsupport.InsuranceFixtures.member;
import static com.wonday.farm.insurance.testsupport.InsuranceFixtures.memberWithRoleCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import com.wonday.farm.insurance.exception.InsuranceApiException;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import com.wonday.farm.work.exception.WorkCompletionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;

// 고용주 완료 demo의 조회 서비스. DemoWorkCompletionRepository는 mock이므로 실제 MySQL의 JOIN·posting.employer_id 필터·
// id desc 정렬·LIMIT 100·left join 변경 행 projection 변환은 실행 검증하지 않는다 (SQL 문자열은 DemoWorkCompletionApiTest에서
// 선언만 확인). 서비스가 세션 고용주 ID를 조회 인자로 넘기고 결과를 매핑하는지를 본다.
@DisplayName("farm-demo 고용주 완료 조회 서비스 (Repository mock)")
class DemoWorkCompletionQueryServiceTest {
	static final long EMPLOYER_ID = 10L;

	private final MemberRepository members = mock(MemberRepository.class);
	private final DemoWorkCompletionRepository repository = mock(DemoWorkCompletionRepository.class);
	private final DemoWorkCompletionQueryService service = new DemoWorkCompletionQueryService(members, repository);

	@BeforeEach
	void employer() {
		given(members.findById(EMPLOYER_ID)).willReturn(Optional.of(member(EMPLOYER_ID, MemberRole.EMPLOYER)));
	}

	@Nested
	@DisplayName("목록")
	class ListAssignments {
		@Test
		@DisplayName("세션 고용주 ID로만 조회하고 저장소 순서대로 다섯 필드를 매핑 (근무일 null 허용)")
		void mapsRows() {
			given(repository.findForEmployer(EMPLOYER_ID)).willReturn(List.of(
				Rows.detail(41, 7, "사과 수확", "근로자 가", LocalDate.parse("2026-11-30"), "GMT+9", null, null, null),
				Rows.detail(40, 8, "배 수확", "근로자 나", null, "GMT+9", null, null, null)));

			var options = service.list(EMPLOYER_ID).assignments();

			verify(repository).findForEmployer(EMPLOYER_ID);
			verifyNoMoreInteractions(repository);
			assertThat(options).extracting(DemoWorkCompletionQueryService.AssignmentOption::assignmentId).containsExactly(41L, 40L);
			assertThat(options.get(0)).isEqualTo(new DemoWorkCompletionQueryService.AssignmentOption(41, 7, "사과 수확", "근로자 가",
				LocalDate.parse("2026-11-30")));
			assertThat(options.get(1).agreedWorkDate()).isNull();
		}

		@Test
		@DisplayName("본인 공고의 확정 근무가 없으면 빈 목록")
		void empty() {
			given(repository.findForEmployer(EMPLOYER_ID)).willReturn(List.of());

			assertThat(service.list(EMPLOYER_ID).assignments()).isEmpty();
		}

		@Test
		@DisplayName("없는 회원·근로자·지원하지 않는 역할은 DB 역할 재확인에서 거절하고 저장소를 조회하지 않는다")
		void requiresEmployerFromDb() {
			given(members.findById(EMPLOYER_ID)).willReturn(Optional.empty());
			assertThatThrownBy(() -> service.list(EMPLOYER_ID)).isInstanceOf(AccessDeniedException.class);
			given(members.findById(EMPLOYER_ID)).willReturn(Optional.of(member(EMPLOYER_ID, MemberRole.WORKER)));
			assertThatThrownBy(() -> service.list(EMPLOYER_ID)).isInstanceOf(AccessDeniedException.class);
			given(members.findById(EMPLOYER_ID)).willReturn(Optional.of(memberWithRoleCode(EMPLOYER_ID, "ADMIN")));
			assertThatThrownBy(() -> service.list(EMPLOYER_ID)).isInstanceOf(AccessDeniedException.class);
			verifyNoInteractions(repository);
		}
	}

	@Nested
	@DisplayName("상세")
	class Detail {
		@Test
		@DisplayName("미완료: 약정 종료 시각은 약정 시간대 offset(+09:00), completedAt null, changeType null")
		void notCompleted() {
			given(repository.findDetailForEmployer(EMPLOYER_ID, 41)).willReturn(Optional.of(
				Rows.detail(41, 7, "사과 수확", "근로자 가", LocalDate.parse("2026-11-30"), "GMT+9",
					LocalDateTime.parse("2026-11-30T17:00"), null, null)));

			var detail = service.detail(EMPLOYER_ID, 41);

			verify(repository).findDetailForEmployer(EMPLOYER_ID, 41);
			assertThat(detail).isEqualTo(new DemoWorkCompletionQueryService.AssignmentDetail(41, 7, "사과 수확", "근로자 가",
				LocalDate.parse("2026-11-30"), "GMT+9", OffsetDateTime.parse("2026-11-30T17:00+09:00"), null, null));
		}

		@Test
		@DisplayName("완료: 저장된 UTC 완료 시각을 +09:00으로 변환하고 마이크로초 유지")
		void completed() {
			given(repository.findDetailForEmployer(EMPLOYER_ID, 41)).willReturn(Optional.of(
				Rows.detail(41, 7, "사과", "가", LocalDate.parse("2026-11-30"), "GMT+9", LocalDateTime.parse("2026-11-30T17:00"),
					LocalDateTime.parse("2026-11-30T08:00:00.000001"), null)));

			assertThat(service.detail(EMPLOYER_ID, 41).completedAt()).isEqualTo(OffsetDateTime.parse("2026-11-30T17:00:00.000001+09:00"));
		}

		@ParameterizedTest(name = "changeType={0}")
		@ValueSource(strings = {"CANCELLED", "EARLY_ENDED"})
		@DisplayName("취소·조기 종료 종류를 그대로 전달")
		void changeType(String type) {
			given(repository.findDetailForEmployer(EMPLOYER_ID, 41)).willReturn(Optional.of(
				Rows.detail(41, 7, "사과", "가", null, "Asia/Seoul", LocalDateTime.parse("2026-11-30T17:00"), null, type)));

			var detail = service.detail(EMPLOYER_ID, 41);

			assertThat(detail.changeType()).isEqualTo(type);
			assertThat(detail.agreedEndAt()).isEqualTo(OffsetDateTime.parse("2026-11-30T17:00+09:00"));
		}

		@Test
		@DisplayName("없는 근무·타인 공고 근무(저장소 빈 결과)는 404 WORK_ASSIGNMENT_NOT_FOUND")
		void notFound() {
			given(repository.findDetailForEmployer(EMPLOYER_ID, 41)).willReturn(Optional.empty());

			assertThatThrownBy(() -> service.detail(EMPLOYER_ID, 41)).isInstanceOfSatisfying(WorkCompletionException.class, error -> {
				assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND);
				assertThat(error.code()).isEqualTo("WORK_ASSIGNMENT_NOT_FOUND");
			});
		}

		@ParameterizedTest(name = "id={0}")
		@ValueSource(longs = {0, -1})
		@DisplayName("0·음수 ID는 회원·저장소 조회 전에 400")
		void invalidId(long id) {
			assertThatThrownBy(() -> service.detail(EMPLOYER_ID, id)).isInstanceOfSatisfying(WorkCompletionException.class,
				error -> assertThat(error.code()).isEqualTo("INVALID_REQUEST"));
			verifyNoInteractions(members, repository);
		}

		@Test
		@DisplayName("근로자 세션은 상세도 DB 역할 재확인에서 거절")
		void workerDenied() {
			given(members.findById(EMPLOYER_ID)).willReturn(Optional.of(member(EMPLOYER_ID, MemberRole.WORKER)));

			assertThatThrownBy(() -> service.detail(EMPLOYER_ID, 41)).isInstanceOf(AccessDeniedException.class);
			verifyNoInteractions(repository);
		}

		@ParameterizedTest(name = "zone=[{0}]")
		@NullSource
		@ValueSource(strings = {"", "Seoul"})
		@DisplayName("시간대 누락·해석 불가는 InsuranceApiException 503")
		void invalidZone(String zone) {
			given(repository.findDetailForEmployer(EMPLOYER_ID, 41)).willReturn(Optional.of(
				Rows.detail(41, 7, "사과", "가", null, zone, LocalDateTime.parse("2026-11-30T17:00"), null, null)));

			assertThatThrownBy(() -> service.detail(EMPLOYER_ID, 41)).isInstanceOfSatisfying(InsuranceApiException.class,
				error -> assertThat(error.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
		}

		@Test
		@DisplayName("약정 종료 누락·서머타임 공백 시각은 503")
		void invalidEnd() {
			given(repository.findDetailForEmployer(EMPLOYER_ID, 41)).willReturn(Optional.of(
				Rows.detail(41, 7, "사과", "가", null, "GMT+9", null, null, null)));
			assertThatThrownBy(() -> service.detail(EMPLOYER_ID, 41)).isInstanceOf(InsuranceApiException.class);

			given(repository.findDetailForEmployer(EMPLOYER_ID, 41)).willReturn(Optional.of(
				Rows.detail(41, 7, "사과", "가", null, "America/New_York", LocalDateTime.parse("2026-03-08T02:30"), null, null)));
			assertThatThrownBy(() -> service.detail(EMPLOYER_ID, 41)).isInstanceOf(InsuranceApiException.class);
		}
	}

	// projection 대역. 실제 Spring Data 네이티브 projection 변환(DATETIME → LocalDateTime 등)은 아니다.
	static final class Rows {
		private Rows() {}

		static DemoWorkCompletionRepository.Detail detail(long assignmentId, long postingId, String title, String workerName,
				LocalDate workDate, String zone, LocalDateTime end, LocalDateTime completedUtc, String changeType) {
			return new DemoWorkCompletionRepository.Detail() {
				public long getAssignmentId() { return assignmentId; }
				public long getJobPostingId() { return postingId; }
				public String getTitle() { return title; }
				public String getWorkerName() { return workerName; }
				public LocalDate getAgreedWorkDate() { return workDate; }
				public String getAgreedTimeZone() { return zone; }
				public LocalDateTime getAgreedEndAt() { return end; }
				public LocalDateTime getCompletedAtUtc() { return completedUtc; }
				public String getChangeType() { return changeType; }
			};
		}
	}
}
