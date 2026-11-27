package com.wonday.farm.application.service;

import static com.wonday.farm.application.testsupport.ApplicationTestData.KEY;
import static com.wonday.farm.application.testsupport.ApplicationTestData.POSTING_ID;
import static com.wonday.farm.application.testsupport.ApplicationTestData.WORKER_ID;
import static com.wonday.farm.application.testsupport.ApplicationTestData.existing;
import static com.wonday.farm.application.testsupport.ApplicationTestData.member;
import static com.wonday.farm.application.testsupport.ApplicationTestData.memberWithRoleCode;
import static com.wonday.farm.application.testsupport.ApplicationTestData.posting;
import static com.wonday.farm.application.testsupport.ApplicationTestData.request;
import static com.wonday.farm.application.testsupport.ApplicationTestData.schedule;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import com.wonday.farm.application.dto.ApplyJobPostingResponse;
import com.wonday.farm.application.entity.ApplicationReceipt;
import com.wonday.farm.application.entity.ApplicationRequestLock;
import com.wonday.farm.application.entity.JobApplication;
import com.wonday.farm.application.exception.ApplicationException;
import com.wonday.farm.application.repository.ApplicationReceiptRepository;
import com.wonday.farm.application.repository.ApplicationRequestLockRepository;
import com.wonday.farm.application.repository.JobApplicationRepository;
import com.wonday.farm.application.validation.ApplicationRequestValidator;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

// 지원 서비스. 실제 엔티티와 고정 Clock, 실제 ApplicationReceiptCodec을 쓰고 Repository는 mock이다.
// Mockito 호출 순서·@Transactional 선언은 코드가 그 순서로 부른다는 것만 보여준다. 실제 MySQL의 행 잠금 대기·
// 동시 요청 직렬화·UNIQUE(현재 회차·회차 번호·요청 키) 위반·롤백은 증명하지 않는다. 직접 만든 서비스라 예외 뒤
// 엔티티 값이 원복되지 않는 것은 트랜잭션이 없기 때문이며 DB 롤백 결함 판단 근거가 아니다.
// 네이티브 SQL(정원·차단 확정 근무·취소 확정 근무) 자체의 결과는 mock 값으로 대신한다.
@DisplayName("APP-02 지원 서비스 (Repository mock, 고정 Clock)")
class JobApplicationServiceTest {
	// 일정 시작: GMT+9 2026-12-10 09:00 = UTC 2026-12-10T00:00:00Z
	private static final Instant START_201 = Instant.parse("2026-12-10T00:00:00Z");
	private static final Instant NOW = Instant.parse("2026-12-01T01:02:03.456789123Z");
	private static final LocalDateTime NOW_UTC = LocalDateTime.parse("2026-12-01T01:02:03.456789");
	private static final OffsetDateTime NOW_KST = OffsetDateTime.parse("2026-12-01T10:02:03.456789+09:00");

	private final MemberRepository members = mock(MemberRepository.class);
	private final JobPostingRepository postings = mock(JobPostingRepository.class);
	private final WorkScheduleRepository schedules = mock(WorkScheduleRepository.class);
	private final JobApplicationRepository applications = mock(JobApplicationRepository.class);
	private final ApplicationRequestLockRepository locks = mock(ApplicationRequestLockRepository.class);
	private final ApplicationReceiptRepository receipts = mock(ApplicationReceiptRepository.class);
	private final ApplicationReceiptCodec codec = new ApplicationReceiptCodec();
	private final AtomicLong ids = new AtomicLong(9000);
	private final Map<Long, WorkSchedule> byId = new HashMap<>();
	private JobPosting posting;

	private JobApplicationService at(Instant now) {
		return new JobApplicationService(members, postings, schedules, applications, locks, receipts, codec,
			Clock.fixed(now, ZoneOffset.UTC));
	}

	private JobApplicationService service() { return at(NOW); }

	@BeforeEach
	void openPostingWithThreeDates() {
		given(members.findById(WORKER_ID)).willReturn(Optional.of(member(WORKER_ID, MemberRole.WORKER)));
		given(locks.lockWorker(WORKER_ID)).willReturn(Optional.of(BeanUtils.instantiateClass(ApplicationRequestLock.class)));
		given(receipts.findByWorkerIdAndRequestKey(anyLong(), anyString())).willReturn(Optional.empty());
		posting = posting(POSTING_ID, 3);
		given(postings.findByIdForUpdate(POSTING_ID)).willReturn(Optional.of(posting));
		addSchedule(201, LocalDate.parse("2026-12-10"), 1);
		addSchedule(202, LocalDate.parse("2026-12-11"), 3);
		addSchedule(203, LocalDate.parse("2026-12-12"), 2);
		given(schedules.findByIdAndJobPosting_Id(anyLong(), anyLong())).willAnswer(invocation -> {
			long id = invocation.getArgument(0);
			long postingId = invocation.getArgument(1);
			return postingId == POSTING_ID ? Optional.ofNullable(byId.get(id)) : Optional.empty();
		});
		given(applications.findBlockingAssignment(anyLong(), anyLong())).willReturn(Optional.empty());
		given(applications.findCurrentForUpdate(anyLong(), anyLong())).willReturn(Optional.empty());
		given(applications.findCancelledAssignment(anyLong())).willReturn(Optional.empty());
		given(applications.saveAllAndFlush(anyList())).willAnswer(invocation -> {
			List<JobApplication> list = invocation.getArgument(0);
			for (var app : list) if (app.getId() == null) ReflectionTestUtils.setField(app, "id", ids.incrementAndGet());
			return list;
		});
		given(receipts.saveAndFlush(any())).willAnswer(invocation -> invocation.getArgument(0));
	}

	private WorkSchedule addSchedule(long id, LocalDate day, int version) {
		var schedule = schedule(posting, id, day, version);
		byId.put(id, schedule);
		return schedule;
	}

	@SuppressWarnings("unchecked")
	private List<List<JobApplication>> savedBatches() {
		ArgumentCaptor<List<JobApplication>> captor = ArgumentCaptor.forClass(List.class);
		verify(applications, org.mockito.Mockito.atLeastOnce()).saveAllAndFlush(captor.capture());
		return captor.getAllValues();
	}

	private ApplicationReceipt savedReceipt() {
		var captor = ArgumentCaptor.forClass(ApplicationReceipt.class);
		verify(receipts).saveAndFlush(captor.capture());
		return captor.getValue();
	}

	private void assertNothingSaved() {
		verify(applications, never()).saveAllAndFlush(anyList());
		verify(receipts, never()).saveAndFlush(any());
	}

	@Nested
	@DisplayName("정상 접수")
	class Success {
		@Test
		@DisplayName("날짜별 PENDING 지원을 1회차·현재 회차·조회 버전·UTC 마이크로초 시각으로 저장하고 날짜마다 별도 ID")
		void storesPendingPerDate() {
			var result = service().apply(WORKER_ID, POSTING_ID, KEY, request(201, 1, 202, 3));

			assertThat(result.replayed()).isFalse();
			var saved = savedBatches().getLast();
			assertThat(saved).hasSize(2);
			assertThat(saved).extracting(JobApplication::getWorkScheduleId).containsExactly(201L, 202L);
			assertThat(saved).extracting(JobApplication::getAppliedTermsVersion).containsExactly(1, 3);
			for (var app : saved) {
				assertThat(app.getWorkerId()).isEqualTo(WORKER_ID);
				assertThat(app.getStatus()).isEqualTo("PENDING");
				assertThat(app.getApplicationRound()).isEqualTo(1);
				assertThat(app.isCurrent()).isTrue();
				assertThat(app.getAppliedAtUtc()).isEqualTo(NOW_UTC);
				assertThat(app.getAppliedAt()).isEqualTo(NOW_UTC);
			}
			assertThat(saved).extracting(JobApplication::getId).doesNotHaveDuplicates();
		}

		@Test
		@DisplayName("응답: 공고 ID와 일정 ID 오름차순 항목(ID·회차·PENDING·버전·+09:00 마이크로초 접수 시각)")
		void responseMapping() {
			var body = service().apply(WORKER_ID, POSTING_ID, KEY, request(202, 3, 201, 1)).body();

			assertThat(body.jobPostingId()).isEqualTo(POSTING_ID);
			assertThat(body.applications()).containsExactly(
				new ApplyJobPostingResponse.Application(9001, 201, 1, "PENDING", 1, NOW_KST),
				new ApplyJobPostingResponse.Application(9002, 202, 1, "PENDING", 3, NOW_KST));
			assertThat(body.applications().getFirst().appliedAt().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
		}

		@Test
		@DisplayName("성공 기록: 소문자 정규화 키·공고·요청 지문·최초 응답 원문·UTC 시각을 저장 (만료 필드 없음)")
		void storesReceipt() {
			var result = service().apply(WORKER_ID, POSTING_ID, KEY.toUpperCase(), request(202, 3, 201, 1));

			var receipt = savedReceipt();
			assertThat(receipt.getWorkerId()).isEqualTo(WORKER_ID);
			assertThat(receipt.getRequestKey()).isEqualTo(KEY);
			assertThat(receipt.getJobPostingId()).isEqualTo(POSTING_ID);
			assertThat(receipt.getRequestFingerprint()).isEqualTo(ApplicationRequestValidator.fingerprint(POSTING_ID,
				ApplicationRequestValidator.schedules(POSTING_ID, request(201, 1, 202, 3))));
			assertThat(codec.decode(receipt.getResponsePayload())).isEqualTo(result.body());
			assertThat(receipt.getCreatedAtUtc()).isEqualTo(NOW_UTC);
			assertThat(java.util.Arrays.stream(ApplicationReceipt.class.getDeclaredFields()).map(f -> f.getName()))
				.noneMatch(name -> name.toLowerCase().contains("expire"));
		}

		@Test
		@DisplayName("일정은 ID 오름차순으로 조회하고, 모든 조회·검증이 끝난 뒤 지원 저장 → 성공 기록 저장")
		void validatesAllBeforeSaving() {
			service().apply(WORKER_ID, POSTING_ID, KEY, request(203, 2, 201, 1, 202, 3));

			var order = inOrder(schedules, applications, receipts);
			for (long id : new long[] {201, 202, 203}) {
				order.verify(schedules).findByIdAndJobPosting_Id(id, POSTING_ID);
				order.verify(applications).findCurrentForUpdate(WORKER_ID, id);
			}
			order.verify(applications).saveAllAndFlush(anyList());
			order.verify(receipts).saveAndFlush(any());
			verify(applications, times(1)).saveAllAndFlush(anyList());
		}

		@Test
		@DisplayName("남은 자리가 1이면(확정 2 / 정원 3) 접수한다. 정원 판단은 확정 근무 집계만 사용 (PENDING 미포함은 SQL 선언 측 확인)")
		void oneSlotLeft() {
			given(schedules.countOccupyingAssignments(201)).willReturn(2L);

			service().apply(WORKER_ID, POSTING_ID, KEY, request(201, 1));

			verify(receipts).saveAndFlush(any());
		}
	}

	@Nested
	@DisplayName("시작 시각 경계")
	class StartBoundary {
		@Test
		@DisplayName("시작 1마이크로초 전·나노초가 절삭돼도 시작 전이면 허용")
		void justBefore() {
			at(START_201.minusNanos(1000)).apply(WORKER_ID, POSTING_ID, KEY, request(201, 1));
			at(START_201.minusNanos(1)).apply(WORKER_ID, POSTING_ID, "6f2b8c1e-9a4d-4c6b-8e2f-1a2b3c4d5e6f", request(202, 3));
			verify(receipts, times(2)).saveAndFlush(any());
		}

		@ParameterizedTest(name = "now={0}")
		@ValueSource(strings = {"2026-12-10T00:00:00Z", "2026-12-10T00:00:00.000001Z", "2026-12-11T00:00:00Z"})
		@DisplayName("정확히 시작 시각·이후는 409 SCHEDULE_STARTED이고 저장 없음")
		void atOrAfter(String now) {
			assertConflict(() -> at(Instant.parse(now)).apply(WORKER_ID, POSTING_ID, KEY, request(201, 1)), "SCHEDULE_STARTED");
			assertNothingSaved();
		}

		@Test
		@DisplayName("둘째 날짜만 시작됐어도 전체 거절, 앞 날짜도 저장하지 않는다 (같은 서버 시각으로 전체 판단)")
		void oneStartedRejectsAll() {
			ReflectionTestUtils.setField(byId.get(202L), "plannedStartAt", LocalDateTime.parse("2026-12-01T09:00"));

			assertConflict(() -> service().apply(WORKER_ID, POSTING_ID, KEY, request(201, 1, 202, 3)), "SCHEDULE_STARTED");
			assertNothingSaved();
		}
	}

	@Nested
	@DisplayName("요청·권한")
	class Access {
		@Test
		@DisplayName("요청 키·일정 검증 실패는 회원·잠금 조회 전에 400")
		void invalidBeforeLookup() {
			assertInvalid(() -> service().apply(WORKER_ID, POSTING_ID, null, request(201, 1)));
			assertInvalid(() -> service().apply(WORKER_ID, POSTING_ID, "not-a-uuid", request(201, 1)));
			assertInvalid(() -> service().apply(WORKER_ID, POSTING_ID, KEY, request(201, 1, 201, 2)));
			assertInvalid(() -> service().apply(WORKER_ID, 0, KEY, request(201, 1)));
			verifyNoInteractions(members, locks, receipts, postings, schedules, applications);
		}

		@Test
		@DisplayName("없는 회원·고용주·지원하지 않는 역할 코드는 AccessDenied이고 잠금·성공 기록·공고를 조회하지 않는다")
		void workerRoleFromDb() {
			given(members.findById(WORKER_ID)).willReturn(Optional.empty());
			assertThatThrownBy(() -> service().apply(WORKER_ID, POSTING_ID, KEY, request(201, 1))).isInstanceOf(AccessDeniedException.class);
			given(members.findById(WORKER_ID)).willReturn(Optional.of(member(WORKER_ID, MemberRole.EMPLOYER)));
			assertThatThrownBy(() -> service().apply(WORKER_ID, POSTING_ID, KEY, request(201, 1))).isInstanceOf(AccessDeniedException.class);
			given(members.findById(WORKER_ID)).willReturn(Optional.of(memberWithRoleCode(WORKER_ID, "ADMIN")));
			assertThatThrownBy(() -> service().apply(WORKER_ID, POSTING_ID, KEY, request(201, 1))).isInstanceOf(AccessDeniedException.class);
			verifyNoInteractions(locks, receipts, postings, schedules, applications);
		}

		@Test
		@DisplayName("요청 잠금 행을 얻지 못하면 503이고 저장 없음")
		void lockUnavailable() {
			given(locks.lockWorker(WORKER_ID)).willReturn(Optional.empty());

			assertThatThrownBy(() -> service().apply(WORKER_ID, POSTING_ID, KEY, request(201, 1)))
				.isInstanceOfSatisfying(ApplicationException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
			assertNothingSaved();
			verifyNoInteractions(postings);
		}
	}

	@Nested
	@DisplayName("공고·일정 조건")
	class Conditions {
		@Test
		@DisplayName("없는 공고는 404 JOB_POSTING_NOT_FOUND")
		void missingPosting() {
			given(postings.findByIdForUpdate(POSTING_ID)).willReturn(Optional.empty());

			assertError(() -> service().apply(WORKER_ID, POSTING_ID, KEY, request(201, 1)), HttpStatus.NOT_FOUND, "JOB_POSTING_NOT_FOUND");
			assertNothingSaved();
		}

		@Test
		@DisplayName("없는 일정·다른 공고의 일정은 404 WORK_SCHEDULE_NOT_FOUND이고 앞 날짜도 저장하지 않는다")
		void missingSchedule() {
			assertError(() -> service().apply(WORKER_ID, POSTING_ID, KEY, request(201, 1, 999, 1)), HttpStatus.NOT_FOUND,
				"WORK_SCHEDULE_NOT_FOUND");
			verify(schedules).findByIdAndJobPosting_Id(999, POSTING_ID);
			assertNothingSaved();
		}

		@ParameterizedTest(name = "{0} → {1}")
		@CsvSource({"PAUSED,RECRUITMENT_PAUSED", "CLOSED,RECRUITMENT_CLOSED", "REMOVED,RECRUITMENT_CANCELLED"})
		@DisplayName("모집 중이 아닌 날짜는 409, 다른 날짜가 정상이어도 전체 거절")
		void notOpen(WorkSchedule.Status status, String code) {
			ReflectionTestUtils.setField(byId.get(202L), "status", status);

			assertConflict(() -> service().apply(WORKER_ID, POSTING_ID, KEY, request(201, 1, 202, 3)), code);
			assertNothingSaved();
		}

		@Test
		@DisplayName("일정 상태 null(해석 불가)은 409 SCHEDULE_STATUS_UNAVAILABLE")
		void unknownStatus() {
			ReflectionTestUtils.setField(byId.get(201L), "status", null);

			assertConflict(() -> service().apply(WORKER_ID, POSTING_ID, KEY, request(201, 1)), "SCHEDULE_STATUS_UNAVAILABLE");
		}

		@Test
		@DisplayName("확정 인원이 정원과 같거나 많으면 409 CAPACITY_FULL")
		void capacityFull() {
			given(schedules.countOccupyingAssignments(202)).willReturn(3L);

			assertConflict(() -> service().apply(WORKER_ID, POSTING_ID, KEY, request(201, 1, 202, 3)), "CAPACITY_FULL");
			assertNothingSaved();
			given(schedules.countOccupyingAssignments(202)).willReturn(4L);
			assertConflict(() -> service().apply(WORKER_ID, POSTING_ID, KEY, request(202, 3)), "CAPACITY_FULL");
		}

		@Test
		@DisplayName("한 일정의 조건 버전이 다르면 409 SCHEDULE_TERMS_CHANGED, 전체 거절")
		void versionMismatch() {
			assertConflict(() -> service().apply(WORKER_ID, POSTING_ID, KEY, request(201, 1, 202, 2)), "SCHEDULE_TERMS_CHANGED");
			assertNothingSaved();
		}

		@ParameterizedTest(name = "zone=[{0}]")
		@NullSource
		@ValueSource(strings = {"", " ", "Seoul", "GMT+99"})
		@DisplayName("공고 시간대 누락·해석 불가는 409 TIME_ZONE_UNAVAILABLE")
		void invalidZone(String zone) {
			ReflectionTestUtils.setField(posting, "timeZone", zone);

			assertConflict(() -> service().apply(WORKER_ID, POSTING_ID, KEY, request(201, 1)), "TIME_ZONE_UNAVAILABLE");
			assertNothingSaved();
		}

		@Test
		@DisplayName("시작 시각 누락·서머타임 공백/중복 현지 시각은 409 SCHEDULE_START_TIME_UNAVAILABLE")
		void unresolvableStart() {
			ReflectionTestUtils.setField(byId.get(201L), "plannedStartAt", null);
			assertConflict(() -> service().apply(WORKER_ID, POSTING_ID, KEY, request(201, 1)), "SCHEDULE_START_TIME_UNAVAILABLE");

			ReflectionTestUtils.setField(posting, "timeZone", "America/New_York");
			ReflectionTestUtils.setField(byId.get(201L), "plannedStartAt", LocalDateTime.parse("2027-03-14T02:30"));
			assertConflict(() -> service().apply(WORKER_ID, POSTING_ID, KEY, request(201, 1)), "SCHEDULE_START_TIME_UNAVAILABLE");
			ReflectionTestUtils.setField(byId.get(201L), "plannedStartAt", LocalDateTime.parse("2027-11-07T01:30"));
			assertConflict(() -> service().apply(WORKER_ID, POSTING_ID, KEY, request(201, 1)), "SCHEDULE_START_TIME_UNAVAILABLE");
			assertNothingSaved();
		}
	}

	@Nested
	@DisplayName("기존 지원과 재지원")
	class Reapplication {
		@Test
		@DisplayName("새 키에 PENDING 기존 지원이 섞이면 새 날짜까지 전체 409 ALREADY_APPLIED")
		void existingPendingMixed() {
			given(applications.findCurrentForUpdate(WORKER_ID, 202L)).willReturn(Optional.of(existing(500, 202, 1, "PENDING", NOW_UTC)));

			assertConflict(() -> service().apply(WORKER_ID, POSTING_ID, KEY, request(201, 1, 202, 3)), "ALREADY_APPLIED");
			assertNothingSaved();
		}

		@Test
		@DisplayName("미선정 1회차 → 과거 행은 상태·시각·회차를 유지하고 current만 해제, 새 ID·2회차·current=true")
		void notSelectedReapply() {
			var past = existing(500, 203, 1, "NOT_SELECTED", LocalDateTime.parse("2026-11-20T00:00"));
			given(applications.findCurrentForUpdate(WORKER_ID, 203L)).willReturn(Optional.of(past));
			given(applications.maxRound(WORKER_ID, 203L)).willReturn(1);

			var body = service().apply(WORKER_ID, POSTING_ID, KEY, request(203, 2)).body();

			assertThat(past.isCurrent()).isFalse();
			assertThat(past.getStatus()).isEqualTo("NOT_SELECTED");
			assertThat(past.getApplicationRound()).isEqualTo(1);
			assertThat(past.getAppliedAt()).isEqualTo(LocalDateTime.parse("2026-11-20T00:00"));
			assertThat(past.getAppliedTermsVersion()).isEqualTo(1);
			var batches = savedBatches();
			assertThat(batches).hasSize(2);
			assertThat(batches.get(0)).containsExactly(past);
			var created = batches.get(1).getFirst();
			assertThat(created.getId()).isNotEqualTo(500L);
			assertThat(created.getApplicationRound()).isEqualTo(2);
			assertThat(created.isCurrent()).isTrue();
			assertThat(created.getAppliedTermsVersion()).isEqualTo(2);
			assertThat(body.applications().getFirst().applicationRound()).isEqualTo(2);
		}

		@Test
		@DisplayName("저장 순서: 과거 current 해제 flush → 새 지원 저장 → 성공 기록 저장")
		void retireThenCreateThenReceipt() {
			var past = existing(500, 203, 1, "NOT_SELECTED", NOW_UTC);
			given(applications.findCurrentForUpdate(WORKER_ID, 203L)).willReturn(Optional.of(past));
			given(applications.maxRound(WORKER_ID, 203L)).willReturn(1);

			service().apply(WORKER_ID, POSTING_ID, KEY, request(201, 1, 203, 2));

			var order = inOrder(applications, receipts);
			order.verify(applications).saveAllAndFlush(List.of(past));
			// saveAllAndFlush 인자는 Iterable이라 목록으로 옮겨 검사한다.
			order.verify(applications).saveAllAndFlush(org.mockito.ArgumentMatchers.<Iterable<JobApplication>>argThat(items -> {
				var list = new java.util.ArrayList<JobApplication>();
				items.forEach(list::add);
				return list.size() == 2 && list.stream().allMatch(JobApplication::isCurrent);
			}));
			order.verify(receipts).saveAndFlush(any());
		}

		@Test
		@DisplayName("취소된 확정 근무에 연결된 SELECTED 회차는 재지원 허용 (과거 확정 연결 행은 상태 그대로)")
		void cancelledAssignmentReapply() {
			var past = existing(500, 203, 1, "SELECTED", NOW_UTC);
			given(applications.findCurrentForUpdate(WORKER_ID, 203L)).willReturn(Optional.of(past));
			given(applications.findCancelledAssignment(500L)).willReturn(Optional.of(77L));
			given(applications.maxRound(WORKER_ID, 203L)).willReturn(1);

			service().apply(WORKER_ID, POSTING_ID, KEY, request(203, 2));

			assertThat(past.getStatus()).isEqualTo("SELECTED");
			assertThat(past.isCurrent()).isFalse();
			verify(receipts).saveAndFlush(any());
		}

		@Test
		@DisplayName("유효 확정·조기 종료·완료(과거 회차 포함, 차단 근무 조회 결과 있음)는 409 ALREADY_CONFIRMED")
		void blockingAssignment() {
			given(applications.findBlockingAssignment(WORKER_ID, 203L)).willReturn(Optional.of(77L));

			assertConflict(() -> service().apply(WORKER_ID, POSTING_ID, KEY, request(201, 1, 203, 2)), "ALREADY_CONFIRMED");
			assertNothingSaved();
		}

		@Test
		@DisplayName("SELECTED 표시만 있고 확정 근무가 없어도 409 ALREADY_CONFIRMED")
		void selectedWithoutAssignment() {
			given(applications.findCurrentForUpdate(WORKER_ID, 203L)).willReturn(Optional.of(existing(500, 203, 1, "SELECTED", NOW_UTC)));

			assertConflict(() -> service().apply(WORKER_ID, POSTING_ID, KEY, request(203, 2)), "ALREADY_CONFIRMED");
			assertNothingSaved();
		}

		@Test
		@DisplayName("알 수 없는 현재 회차 상태는 409 APPLICATION_STATUS_UNAVAILABLE")
		void unknownCurrentStatus() {
			given(applications.findCurrentForUpdate(WORKER_ID, 203L)).willReturn(Optional.of(existing(500, 203, 1, "WITHDRAWN", NOW_UTC)));

			assertConflict(() -> service().apply(WORKER_ID, POSTING_ID, KEY, request(203, 2)), "APPLICATION_STATUS_UNAVAILABLE");
		}

		@Test
		@DisplayName("앞 날짜가 재지원 대상이고 뒤 날짜가 실패하면 앞 회차의 current를 먼저 바꾸지 않는다")
		void noEarlyRetire() {
			var past = existing(500, 201, 1, "NOT_SELECTED", NOW_UTC);
			given(applications.findCurrentForUpdate(WORKER_ID, 201L)).willReturn(Optional.of(past));
			given(applications.maxRound(WORKER_ID, 201L)).willReturn(1);

			assertConflict(() -> service().apply(WORKER_ID, POSTING_ID, KEY, request(201, 1, 202, 9)), "SCHEDULE_TERMS_CHANGED");
			assertThat(past.isCurrent()).isTrue();
			assertNothingSaved();
		}

		@Test
		@DisplayName("시작 시각 검사로 실패해도 재지원 대상 current를 바꾸지 않는다")
		void noRetireWhenStarted() {
			var past = existing(500, 201, 1, "NOT_SELECTED", NOW_UTC);
			given(applications.findCurrentForUpdate(WORKER_ID, 201L)).willReturn(Optional.of(past));
			given(applications.maxRound(WORKER_ID, 201L)).willReturn(1);

			assertConflict(() -> at(START_201).apply(WORKER_ID, POSTING_ID, KEY, request(201, 1)), "SCHEDULE_STARTED");
			assertThat(past.isCurrent()).isTrue();
		}

		@Test
		@DisplayName("회차 상한(Integer.MAX_VALUE)은 409 APPLICATION_ROUND_EXHAUSTED, 음수 집계는 503")
		void roundLimits() {
			given(applications.maxRound(WORKER_ID, 201L)).willReturn(Integer.MAX_VALUE);
			assertConflict(() -> service().apply(WORKER_ID, POSTING_ID, KEY, request(201, 1)), "APPLICATION_ROUND_EXHAUSTED");

			given(applications.maxRound(WORKER_ID, 201L)).willReturn(-1);
			assertError(() -> service().apply(WORKER_ID, POSTING_ID, KEY, request(201, 1)), HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE");
			assertNothingSaved();
		}

		@Test
		@DisplayName("다음 회차는 현재 회차가 아니라 전체 최대 회차 + 1 (최대 4 → 5)")
		void nextRoundFromMax() {
			given(applications.findCurrentForUpdate(WORKER_ID, 201L)).willReturn(Optional.of(existing(500, 201, 4, "NOT_SELECTED", NOW_UTC)));
			given(applications.maxRound(WORKER_ID, 201L)).willReturn(4);

			assertThat(service().apply(WORKER_ID, POSTING_ID, KEY, request(201, 1)).body().applications().getFirst().applicationRound()).isEqualTo(5);
		}
	}

	@Nested
	@DisplayName("같은 요청 키 재요청")
	class Replay {
		private ApplyJobPostingResponse first;

		@BeforeEach
		void firstSucceeded() {
			first = service().apply(WORKER_ID, POSTING_ID, KEY, request(201, 1, 202, 3)).body();
			var receipt = savedReceipt();
			given(receipts.findByWorkerIdAndRequestKey(WORKER_ID, KEY)).willReturn(Optional.of(receipt));
			org.mockito.Mockito.clearInvocations(postings, schedules, applications, receipts);
		}

		@Test
		@DisplayName("같은 키·같은 내용(순서·대소문자 무관)이면 replayed=true와 최초 결과, 새 지원·회차·성공 기록 없음")
		void returnsFirstResult() {
			var result = at(NOW.plusSeconds(3600)).apply(WORKER_ID, POSTING_ID, KEY.toUpperCase(), request(202, 3, 201, 1));

			assertThat(result.replayed()).isTrue();
			assertThat(result.body()).isEqualTo(first);
			assertThat(result.body().applications().getFirst().appliedAt()).isEqualTo(NOW_KST);
			verify(receipts).findByWorkerIdAndRequestKey(WORKER_ID, KEY);
			assertNothingSaved();
			verifyNoInteractions(postings, schedules);
			verify(applications, never()).findCurrentForUpdate(anyLong(), anyLong());
		}

		@Test
		@DisplayName("그사이 모집 마감·버전 변경·시작 시각 경과·공고 삭제가 있어도 최초 결과 유지 (현재 조건을 다시 적용하지 않음)")
		void ignoresCurrentConditions() {
			ReflectionTestUtils.setField(byId.get(201L), "status", WorkSchedule.Status.CLOSED);
			ReflectionTestUtils.setField(byId.get(202L), "termsVersion", 9);
			given(postings.findByIdForUpdate(POSTING_ID)).willReturn(Optional.empty());

			var result = at(START_201.plusSeconds(86400 * 5)).apply(WORKER_ID, POSTING_ID, KEY, request(201, 1, 202, 3));

			assertThat(result.replayed()).isTrue();
			assertThat(result.body()).isEqualTo(first);
		}

		@Test
		@DisplayName("같은 키에 다른 날짜·버전·공고면 409 IDEMPOTENCY_KEY_CONFLICT이고 저장 없음")
		void differentContent() {
			assertConflict(() -> service().apply(WORKER_ID, POSTING_ID, KEY, request(201, 1)), "IDEMPOTENCY_KEY_CONFLICT");
			assertConflict(() -> service().apply(WORKER_ID, POSTING_ID, KEY, request(201, 1, 202, 4)), "IDEMPOTENCY_KEY_CONFLICT");
			assertConflict(() -> service().apply(WORKER_ID, POSTING_ID, KEY, request(201, 1, 203, 2)), "IDEMPOTENCY_KEY_CONFLICT");
			assertConflict(() -> service().apply(WORKER_ID, 102, KEY, request(201, 1, 202, 3)), "IDEMPOTENCY_KEY_CONFLICT");
			assertNothingSaved();
			verifyNoInteractions(postings);
		}

		@Test
		@DisplayName("재요청에도 현재 DB 역할 검사를 먼저 한다 (근로자가 아니면 성공 기록을 읽지 않음)")
		void roleCheckedOnReplay() {
			given(members.findById(WORKER_ID)).willReturn(Optional.of(member(WORKER_ID, MemberRole.EMPLOYER)));

			assertThatThrownBy(() -> service().apply(WORKER_ID, POSTING_ID, KEY, request(201, 1, 202, 3))).isInstanceOf(AccessDeniedException.class);
			verifyNoInteractions(receipts);
		}

		@Test
		@DisplayName("성공 기록은 근로자 ID와 키로만 찾는다: 다른 근로자의 같은 키는 새 요청으로 처리")
		void receiptScopedToWorker() {
			given(members.findById(6L)).willReturn(Optional.of(member(6L, MemberRole.WORKER)));
			given(locks.lockWorker(6L)).willReturn(Optional.of(BeanUtils.instantiateClass(ApplicationRequestLock.class)));

			var result = service().apply(6L, POSTING_ID, KEY, request(201, 1, 202, 3));

			assertThat(result.replayed()).isFalse();
			verify(receipts).findByWorkerIdAndRequestKey(6L, KEY);
		}

		@Test
		@DisplayName("[특성화] 보관 응답의 공고 ID가 기록과 다르게 손상돼 있으면 IllegalStateException (500으로 처리)")
		void corruptedReceipt() {
			var corrupted = new ApplicationReceipt(WORKER_ID, KEY, POSTING_ID, savedFingerprint(),
				codec.encode(new ApplyJobPostingResponse(999, first.applications())), NOW_UTC);
			given(receipts.findByWorkerIdAndRequestKey(WORKER_ID, KEY)).willReturn(Optional.of(corrupted));

			assertThatThrownBy(() -> service().apply(WORKER_ID, POSTING_ID, KEY, request(201, 1, 202, 3))).isInstanceOf(IllegalStateException.class);
		}

		private String savedFingerprint() {
			return ApplicationRequestValidator.fingerprint(POSTING_ID, ApplicationRequestValidator.schedules(POSTING_ID, request(201, 1, 202, 3)));
		}
	}

	@Nested
	@DisplayName("잠금 순서·예외 전파 (mock 호출 기준)")
	class LockingAndFailures {
		@Test
		@DisplayName("역할 확인 → 요청 잠금 행 준비 → 근로자 잠금 → 성공 기록 확인 → 공고 잠금 → 일정 오름차순 → 현재 지원 잠금")
		void lockOrder() {
			service().apply(WORKER_ID, POSTING_ID, KEY, request(202, 3, 201, 1));

			var order = inOrder(members, locks, receipts, postings, schedules, applications);
			order.verify(members).findById(WORKER_ID);
			order.verify(locks).ensureExists(WORKER_ID);
			order.verify(locks).lockWorker(WORKER_ID);
			order.verify(receipts).findByWorkerIdAndRequestKey(WORKER_ID, KEY);
			order.verify(postings).findByIdForUpdate(POSTING_ID);
			order.verify(schedules).findByIdAndJobPosting_Id(201, POSTING_ID);
			order.verify(applications).findCurrentForUpdate(WORKER_ID, 201);
			order.verify(schedules).findByIdAndJobPosting_Id(202, POSTING_ID);
			order.verify(applications).findCurrentForUpdate(WORKER_ID, 202);
		}

		@Test
		@DisplayName("지원 저장 예외(DataIntegrityViolation)는 그대로 전파되고 성공 기록을 저장하지 않는다")
		void applicationSaveFailurePropagates() {
			given(applications.saveAllAndFlush(anyList())).willThrow(new DataIntegrityViolationException("uq_application_current"));

			assertThatThrownBy(() -> service().apply(WORKER_ID, POSTING_ID, KEY, request(201, 1))).isInstanceOf(DataIntegrityViolationException.class);
			verify(receipts, never()).saveAndFlush(any());
		}

		@Test
		@DisplayName("성공 기록 저장 예외도 삼키지 않고 전파한다 (201 결과를 반환하지 않음)")
		void receiptSaveFailurePropagates() {
			given(receipts.saveAndFlush(any())).willThrow(new DataIntegrityViolationException("uq_application_receipt_worker_key"));

			assertThatThrownBy(() -> service().apply(WORKER_ID, POSTING_ID, KEY, request(201, 1))).isInstanceOf(DataIntegrityViolationException.class);
		}

		@Test
		@DisplayName("READ_COMMITTED 트랜잭션·jobClock 한정자 선언 (실제 트랜잭션 동작 아님)")
		void declarations() throws Exception {
			var method = JobApplicationService.class.getMethod("apply", long.class, long.class, String.class,
				com.wonday.farm.application.dto.ApplyJobPostingRequest.class);
			assertThat(method.getAnnotation(Transactional.class).isolation()).isEqualTo(Isolation.READ_COMMITTED);
			assertThat(method.getAnnotation(Transactional.class).readOnly()).isFalse();
			assertThat(JobApplicationService.class.getConstructors()[0].getParameters()[7].getAnnotation(Qualifier.class).value())
				.isEqualTo("jobClock");
		}
	}

	private static void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
		assertError(call, HttpStatus.CONFLICT, code);
	}

	private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
		assertError(call, HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
	}

	private static void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, HttpStatus status, String code) {
		assertThatThrownBy(call).isInstanceOfSatisfying(ApplicationException.class, error -> {
			assertThat(error.status()).isEqualTo(status);
			assertThat(error.code()).isEqualTo(code);
		});
	}

}
