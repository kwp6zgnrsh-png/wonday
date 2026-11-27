package com.wonday.farm.demo.application;

import static com.wonday.farm.application.testsupport.ApplicationTestData.WORKER_ID;
import static com.wonday.farm.application.testsupport.ApplicationTestData.member;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import com.wonday.farm.application.entity.ApplicationRequestLock;
import com.wonday.farm.application.exception.ApplicationException;
import com.wonday.farm.application.repository.ApplicationRequestLockRepository;
import com.wonday.farm.job.entity.JobPosting;
import com.wonday.farm.job.entity.WorkSchedule;
import com.wonday.farm.job.repository.JobPostingRepository;
import com.wonday.farm.job.repository.WorkScheduleRepository;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.BeanUtils;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

// farm-demo 지원 이력·시연 준비 서비스. Repository는 mock이므로 실제 INSERT·JOIN·LIMIT 500·정렬 실행, 개발 DB 변경은 없다.
// 고정 Clock: UTC 2026-11-30T15:30:00.123456789Z = GMT+9 2026-12-01 00:30 (날짜가 GMT+9 기준으로 넘어가는 경계).
@DisplayName("farm-demo APP-02 이력·시연 준비 서비스 (Repository mock, 고정 Clock)")
class DemoJobApplicationServiceTest {
	private static final Instant NOW = Instant.parse("2026-11-30T15:30:00.123456789Z");
	private static final String MARKER = "[APP-02 시연·근로자 5·2026-12-01]";

	private final MemberRepository members = mock(MemberRepository.class);
	private final JobPostingRepository postings = mock(JobPostingRepository.class);
	private final WorkScheduleRepository schedules = mock(WorkScheduleRepository.class);
	private final DemoJobApplicationRepository repository = mock(DemoJobApplicationRepository.class);
	private final ApplicationRequestLockRepository locks = mock(ApplicationRequestLockRepository.class);
	private final DemoJobApplicationService service = new DemoJobApplicationService(members, postings, schedules, repository, locks,
		Clock.fixed(NOW, ZoneOffset.UTC));

	@BeforeEach
	void worker() {
		given(members.findById(WORKER_ID)).willReturn(Optional.of(member(WORKER_ID, MemberRole.WORKER)));
	}

	static DemoJobApplicationRepository.History row(long id, long scheduleId, int round, String status, int current,
			Integer version, Long assignmentId, String changeType) {
		return new DemoJobApplicationRepository.History() {
			public long getApplicationId() { return id; }
			public long getWorkScheduleId() { return scheduleId; }
			public int getApplicationRound() { return round; }
			public String getStatus() { return status; }
			public int getCurrentFlag() { return current; }
			public Integer getAppliedTermsVersion() { return version; }
			public Long getAssignmentId() { return assignmentId; }
			public String getChangeType() { return changeType; }
		};
	}

	@Nested
	@DisplayName("본인 이력")
	class History {
		@Test
		@DisplayName("세션 근로자 ID와 공고 ID로 조회하고 과거·현재 회차, 접수 버전(null 포함), 확정·취소 연결을 그대로 매핑")
		void maps() {
			given(postings.existsById(101L)).willReturn(true);
			given(repository.findOwnHistory(WORKER_ID, 101L)).willReturn(List.of(
				row(9010, 203, 2, "PENDING", 1, 2, null, null),
				row(9003, 203, 1, "NOT_SELECTED", 0, 1, null, null),
				row(8001, 202, 1, "SELECTED", 1, null, 77L, "CANCELLED")));

			var list = service.history(WORKER_ID, 101).applications();

			verify(repository).findOwnHistory(WORKER_ID, 101L);
			assertThat(list).containsExactly(
				new DemoJobApplicationService.ApplicationHistory(9010, 203, 2, "PENDING", true, 2, null, null),
				new DemoJobApplicationService.ApplicationHistory(9003, 203, 1, "NOT_SELECTED", false, 1, null, null),
				new DemoJobApplicationService.ApplicationHistory(8001, 202, 1, "SELECTED", true, null, 77L, "CANCELLED"));
		}

		@Test
		@DisplayName("이력이 없으면 빈 목록")
		void empty() {
			given(postings.existsById(101L)).willReturn(true);
			given(repository.findOwnHistory(WORKER_ID, 101L)).willReturn(List.of());

			assertThat(service.history(WORKER_ID, 101).applications()).isEmpty();
		}

		@Test
		@DisplayName("없는 공고는 404 JOB_POSTING_NOT_FOUND이고 이력을 조회하지 않는다")
		void missingPosting() {
			assertThatThrownBy(() -> service.history(WORKER_ID, 101)).isInstanceOfSatisfying(ApplicationException.class, e -> {
				assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND);
				assertThat(e.code()).isEqualTo("JOB_POSTING_NOT_FOUND");
			});
			verifyNoInteractions(repository);
		}

		@ParameterizedTest(name = "postingId={0}")
		@ValueSource(longs = {0, -1})
		@DisplayName("0 이하 공고 ID는 회원 조회 전에 400")
		void invalidId(long id) {
			assertThatThrownBy(() -> service.history(WORKER_ID, id)).isInstanceOfSatisfying(ApplicationException.class,
				e -> assertThat(e.code()).isEqualTo("INVALID_REQUEST"));
			verifyNoInteractions(members, repository);
		}

		@Test
		@DisplayName("근로자가 아니거나 없는 회원은 AccessDenied, 공고·이력 조회 없음")
		void requiresWorker() {
			given(members.findById(WORKER_ID)).willReturn(Optional.of(member(WORKER_ID, MemberRole.EMPLOYER)));
			assertThatThrownBy(() -> service.history(WORKER_ID, 101)).isInstanceOf(AccessDeniedException.class);
			given(members.findById(WORKER_ID)).willReturn(Optional.empty());
			assertThatThrownBy(() -> service.history(WORKER_ID, 101)).isInstanceOf(AccessDeniedException.class);
			verifyNoInteractions(postings, repository);
		}
	}

	@Nested
	@DisplayName("시연 준비")
	class Fixture {
		private final AtomicLong scheduleIds = new AtomicLong(700);

		@BeforeEach
		void demoEmployer() {
			given(members.findByLoginId("demo-employer")).willReturn(Optional.of(member(10L, MemberRole.EMPLOYER)));
			given(locks.lockWorker(WORKER_ID)).willReturn(Optional.of(BeanUtils.instantiateClass(ApplicationRequestLock.class)));
			given(repository.findFixturePosting(10L, MARKER)).willReturn(Optional.empty());
			given(postings.saveAndFlush(any())).willAnswer(invocation -> {
				JobPosting posting = invocation.getArgument(0);
				ReflectionTestUtils.setField(posting, "id", 555L);
				return posting;
			});
			given(schedules.saveAllAndFlush(anyList())).willAnswer(invocation -> {
				List<WorkSchedule> list = invocation.getArgument(0);
				for (var schedule : list) ReflectionTestUtils.setField(schedule, "id", scheduleIds.incrementAndGet());
				return list;
			});
			given(repository.insertNotSelectedFixture(anyLong(), anyLong(), any())).willReturn(1);
		}

		@Test
		@DisplayName("새 준비: demo-employer 소유·GMT+9·정원 3의 가상 공고와 오늘(GMT+9)+7·8·9일 09~17시 일정 세 개")
		@SuppressWarnings("unchecked")
		void createsPostingAndThreeFutureDates() {
			var result = service.prepareFixture(WORKER_ID);

			assertThat(result).isEqualTo(new DemoJobApplicationService.FixtureResponse(555, true));
			var posting = ArgumentCaptor.forClass(JobPosting.class);
			verify(postings).saveAndFlush(posting.capture());
			assertThat(posting.getValue().getTitle()).isEqualTo(MARKER);
			assertThat(posting.getValue().getEmployer().getId()).isEqualTo(10L);
			assertThat(posting.getValue().getTimeZone()).isEqualTo("GMT+9");
			assertThat(posting.getValue().getHeadcount()).isEqualTo(3);
			ArgumentCaptor<List<WorkSchedule>> created = ArgumentCaptor.forClass(List.class);
			verify(schedules).saveAllAndFlush(created.capture());
			assertThat(created.getValue()).extracting(WorkSchedule::getWorkDate)
				.containsExactly(LocalDate.parse("2026-12-08"), LocalDate.parse("2026-12-09"), LocalDate.parse("2026-12-10"));
			for (var schedule : created.getValue()) {
				assertThat(schedule.getPlannedStartAt()).isEqualTo(schedule.getWorkDate().atTime(9, 0));
				assertThat(schedule.getPlannedEndAt()).isEqualTo(schedule.getWorkDate().atTime(17, 0));
				assertThat(schedule.getStatus()).isEqualTo(WorkSchedule.Status.OPEN);
				assertThat(schedule.getTermsVersion()).isEqualTo(1);
				assertThat(schedule.getJobPosting().getId()).isEqualTo(555L);
			}
		}

		@Test
		@DisplayName("미선정 1회차 INSERT는 새로 만든 셋째 일정 ID(703)에만, 시각은 서버 시각 1시간 전 UTC 마이크로초")
		void insertsOnlyForThirdNewSchedule() {
			service.prepareFixture(WORKER_ID);

			verify(repository).insertNotSelectedFixture(WORKER_ID, 703L, LocalDateTime.parse("2026-11-30T14:30:00.123456"));
			verify(repository).findFixturePosting(10L, MARKER);
			verifyNoMoreInteractions(repository);
		}

		@Test
		@DisplayName("같은 근로자·같은 GMT+9 날짜에 이미 준비했다면 기존 공고를 반환하고 저장·INSERT 없음")
		void existingFixture() {
			given(repository.findFixturePosting(10L, MARKER)).willReturn(Optional.of(444L));

			var result = service.prepareFixture(WORKER_ID);

			assertThat(result).isEqualTo(new DemoJobApplicationService.FixtureResponse(444, false));
			verify(postings, never()).saveAndFlush(any());
			verify(schedules, never()).saveAllAndFlush(anyList());
			verify(repository, never()).insertNotSelectedFixture(anyLong(), anyLong(), any());
		}

		@Test
		@DisplayName("다른 근로자는 자기 ID가 든 다른 표식으로 찾는다 (다른 근로자의 시연 공고를 반환하지 않음)")
		void markerPerWorker() {
			given(members.findById(6L)).willReturn(Optional.of(member(6L, MemberRole.WORKER)));
			given(locks.lockWorker(6L)).willReturn(Optional.of(BeanUtils.instantiateClass(ApplicationRequestLock.class)));

			service.prepareFixture(6L);

			verify(repository).findFixturePosting(10L, "[APP-02 시연·근로자 6·2026-12-01]");
		}

		@Test
		@DisplayName("근로자 확인 → 요청 잠금 → demo-employer 확인 → 표식 조회 순서 (실제 지원과 같은 근로자 잠금)")
		void order() {
			service.prepareFixture(WORKER_ID);

			var order = inOrder(members, locks, repository, postings);
			order.verify(members).findById(WORKER_ID);
			order.verify(locks).ensureExists(WORKER_ID);
			order.verify(locks).lockWorker(WORKER_ID);
			order.verify(members).findByLoginId("demo-employer");
			order.verify(repository).findFixturePosting(10L, MARKER);
			order.verify(postings).saveAndFlush(any());
		}

		@Test
		@DisplayName("demo-employer가 없거나 고용주가 아니면 409 DEMO_EMPLOYER_UNAVAILABLE이고 아무것도 만들지 않는다")
		void demoEmployerRequired() {
			given(members.findByLoginId("demo-employer")).willReturn(Optional.empty());
			assertConflict("DEMO_EMPLOYER_UNAVAILABLE");
			given(members.findByLoginId("demo-employer")).willReturn(Optional.of(member(10L, MemberRole.WORKER)));
			assertConflict("DEMO_EMPLOYER_UNAVAILABLE");
			verify(postings, never()).saveAndFlush(any());
			verifyNoInteractions(repository);
		}

		@Test
		@DisplayName("근로자가 아니면 잠금·생성 전에 AccessDenied")
		void requiresWorker() {
			given(members.findById(WORKER_ID)).willReturn(Optional.of(member(WORKER_ID, MemberRole.EMPLOYER)));

			assertThatThrownBy(() -> service.prepareFixture(WORKER_ID)).isInstanceOf(AccessDeniedException.class);
			verifyNoInteractions(locks, repository, postings, schedules);
		}

		@Test
		@DisplayName("요청 잠금 실패 또는 INSERT 행 수가 1이 아니면 503")
		void unavailable() {
			given(locks.lockWorker(WORKER_ID)).willReturn(Optional.empty());
			assertUnavailable();
			verify(postings, never()).saveAndFlush(any());

			given(locks.lockWorker(WORKER_ID)).willReturn(Optional.of(BeanUtils.instantiateClass(ApplicationRequestLock.class)));
			given(repository.insertNotSelectedFixture(anyLong(), anyLong(), any())).willReturn(0);
			assertUnavailable();
		}

		private void assertConflict(String code) {
			assertThatThrownBy(() -> service.prepareFixture(WORKER_ID)).isInstanceOfSatisfying(ApplicationException.class, e -> {
				assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
				assertThat(e.code()).isEqualTo(code);
			});
		}

		private void assertUnavailable() {
			assertThatThrownBy(() -> service.prepareFixture(WORKER_ID)).isInstanceOfSatisfying(ApplicationException.class,
				e -> assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
		}
	}
}
