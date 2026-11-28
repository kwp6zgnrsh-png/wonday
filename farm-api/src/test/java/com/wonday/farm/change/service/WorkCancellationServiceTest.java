package com.wonday.farm.change.service;

import static com.wonday.farm.insurance.testsupport.InsuranceFixtures.assignment;
import static com.wonday.farm.insurance.testsupport.InsuranceFixtures.change;
import static com.wonday.farm.insurance.testsupport.InsuranceFixtures.member;
import static com.wonday.farm.insurance.testsupport.InsuranceFixtures.stream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import com.wonday.farm.change.dto.WorkCancellationRequest;
import com.wonday.farm.change.entity.WorkChange;
import com.wonday.farm.change.entity.WorkChange.Reason;
import com.wonday.farm.change.entity.WorkChange.Type;
import com.wonday.farm.change.entity.WorkChange.WeatherReason;
import com.wonday.farm.change.exception.WorkCancellationException;
import com.wonday.farm.change.repository.WorkChangeRepository;
import com.wonday.farm.insurance.entity.ChangeStream;
import com.wonday.farm.insurance.exception.InsuranceApiException;
import com.wonday.farm.insurance.repository.ChangeStreamRepository;
import com.wonday.farm.insurance.repository.WorkChangeVersionRepository;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import com.wonday.farm.work.entity.WorkAssignment;
import com.wonday.farm.work.repository.WorkAssignmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

// 실제 WorkCancellationService + 실제 WorkChangeRecorder, 저장소만 mock. 트랜잭션은 선언만 확인한다(실제 커밋·롤백 미검증).
@DisplayName("CHANGE-01·02 취소 서비스 (실제 서비스+기록기, 저장소 mock)")
class WorkCancellationServiceTest {
	private static final long EMPLOYER = 10L, WORKER = 5L, ASSIGNMENT = 31L;
	private static final Instant NOW = Instant.parse("2026-11-28T06:00:00.123456789Z");

	private final MemberRepository members = mock(MemberRepository.class);
	private final WorkAssignmentRepository assignments = mock(WorkAssignmentRepository.class);
	private final WorkChangeRepository changes = mock(WorkChangeRepository.class);
	private final ChangeStreamRepository streams = mock(ChangeStreamRepository.class);
	private final WorkChangeVersionRepository versions = mock(WorkChangeVersionRepository.class);
	private final WorkChangeRecorder recorder = new WorkChangeRecorder(members, assignments, changes, streams, versions,
		Clock.fixed(NOW, ZoneOffset.UTC));
	private final WorkCancellationService service = new WorkCancellationService(recorder);
	private ChangeStream stream;
	private WorkAssignment stored;

	@BeforeEach
	void owned() {
		given(members.findById(EMPLOYER)).willReturn(Optional.of(member(EMPLOYER, MemberRole.EMPLOYER)));
		given(assignments.findOwnership(ASSIGNMENT)).willReturn(Optional.of(new WorkAssignmentRepository.Ownership() {
			public long getWorkerId() { return WORKER; }
			public long getEmployerId() { return EMPLOYER; }
		}));
		stream = stream(WORKER, 0);
		given(streams.lockWorker(WORKER)).willReturn(Optional.of(stream));
		stored = assignment(ASSIGNMENT);
		given(assignments.findByIdForUpdate(ASSIGNMENT)).willReturn(Optional.of(stored));
		given(changes.findForUpdate(ASSIGNMENT)).willReturn(Optional.empty());
		given(changes.saveAndFlush(any())).willAnswer(invocation -> {
			WorkChange saved = invocation.getArgument(0);
			ReflectionTestUtils.setField(saved, "id", 77L);
			return saved;
		});
	}

	@Test
	void firstCancellationResponseUsesAgreedOffsetAndStringVersion() {
		var response = service.cancel(EMPLOYER, ASSIGNMENT, new WorkCancellationRequest("WEATHER", "RAIN", "  비가 옵니다 "));
		assertThat(response.assignmentId()).isEqualTo(ASSIGNMENT);
		assertThat(response.changeId()).isEqualTo(77L);
		assertThat(response.changeType()).isEqualTo(Type.CANCELLED);
		assertThat(response.reasonCode()).isEqualTo(Reason.WEATHER);
		assertThat(response.weatherReasonCode()).isEqualTo(WeatherReason.RAIN);
		assertThat(response.reasonDetail()).isEqualTo("비가 옵니다");
		var expected = OffsetDateTime.parse("2026-11-28T15:00:00.123456+09:00");
		assertThat(response.effectiveAt()).isEqualTo(expected);
		assertThat(response.effectiveAt().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
		assertThat(response.recordedAt()).isEqualTo(expected);
		assertThat(response.updatedAt()).isEqualTo(expected);
		assertThat(response.sourceVersion()).isEqualTo("1");
	}

	@Test
	void nullWeatherAndDetailStayNull() {
		var response = service.cancel(EMPLOYER, ASSIGNMENT, new WorkCancellationRequest("OTHER", null, "   "));
		assertThat(response.weatherReasonCode()).isNull();
		assertThat(response.reasonDetail()).isNull();
	}

	@Test
	void replayReturnsStoredIdVersionAndOriginalTimes() {
		var previous = change(500L, ASSIGNMENT, Type.CANCELLED, Reason.FARM_CIRCUMSTANCES, null, "사정",
			LocalDateTime.parse("2026-11-27T01:00:00.000001"), LocalDateTime.parse("2026-11-27T01:00:00.000002"));
		ReflectionTestUtils.setField(previous, "updatedAt", LocalDateTime.parse("2026-11-27T02:00:00.000003"));
		ReflectionTestUtils.setField(previous, "sourceVersion", 3L);
		given(changes.findForUpdate(ASSIGNMENT)).willReturn(Optional.of(previous));
		var response = service.cancel(EMPLOYER, ASSIGNMENT, new WorkCancellationRequest("FARM_CIRCUMSTANCES", null, " 사정 "));
		assertThat(response.changeId()).isEqualTo(500L);
		assertThat(response.sourceVersion()).isEqualTo("3");
		assertThat(response.effectiveAt()).isEqualTo(OffsetDateTime.parse("2026-11-27T10:00:00.000001+09:00"));
		assertThat(response.recordedAt()).isEqualTo(OffsetDateTime.parse("2026-11-27T10:00:00.000002+09:00"));
		assertThat(response.updatedAt()).isEqualTo(OffsetDateTime.parse("2026-11-27T11:00:00.000003+09:00"));
		verify(changes, never()).saveAndFlush(any());
		verifyNoInteractions(versions);
	}

	@Test
	void replayWithUnusableStoredZoneIs503() {
		var previous = change(500L, ASSIGNMENT, Type.CANCELLED, Reason.OTHER, null, null,
			LocalDateTime.parse("2026-11-27T01:00"), LocalDateTime.parse("2026-11-27T01:00"));
		given(changes.findForUpdate(ASSIGNMENT)).willReturn(Optional.of(previous));
		ReflectionTestUtils.setField(stored, "agreedTimeZone", "broken/zone");
		assertThatThrownBy(() -> service.cancel(EMPLOYER, ASSIGNMENT, new WorkCancellationRequest("OTHER", null, null)))
			.isInstanceOfSatisfying(InsuranceApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
	}

	@Test
	void versionFailurePropagatesThroughService() {
		willThrow(new DataAccessResourceFailureException("down")).given(versions).save(any());
		assertThatThrownBy(() -> service.cancel(EMPLOYER, ASSIGNMENT, new WorkCancellationRequest("OTHER", null, null)))
			.isInstanceOf(DataAccessResourceFailureException.class);
	}

	@Test
	void invalidInputNeverReachesRecorder() {
		var mocked = mock(WorkChangeRecorder.class);
		var guarded = new WorkCancellationService(mocked);
		var ok = new WorkCancellationRequest("OTHER", null, null);
		for (var call : new org.assertj.core.api.ThrowableAssert.ThrowingCallable[] {
				() -> guarded.cancel(0, ASSIGNMENT, ok), () -> guarded.cancel(EMPLOYER, 0, ok), () -> guarded.cancel(-1, -1, ok),
				() -> guarded.cancel(EMPLOYER, ASSIGNMENT, null), () -> guarded.cancel(EMPLOYER, ASSIGNMENT, new WorkCancellationRequest(null, null, null)),
				() -> guarded.cancel(EMPLOYER, ASSIGNMENT, new WorkCancellationRequest("WEATHER", null, null)),
				() -> guarded.cancel(EMPLOYER, ASSIGNMENT, new WorkCancellationRequest("OTHER", "RAIN", null)),
				() -> guarded.cancel(EMPLOYER, ASSIGNMENT, new WorkCancellationRequest("OTHER", null, "x".repeat(501)))}) {
			assertThatThrownBy(call).isInstanceOfSatisfying(WorkCancellationException.class,
				e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
		}
		verifyNoInteractions(mocked);
	}

	@Test
	void normalizedValuesArePassedToRecorder() {
		var mocked = mock(WorkChangeRecorder.class);
		var change = change(9L, ASSIGNMENT, Type.CANCELLED, Reason.WEATHER, WeatherReason.WIND, "강풍",
			LocalDateTime.parse("2026-11-27T01:00"), LocalDateTime.parse("2026-11-27T01:00"));
		given(mocked.cancelBeforeStart(EMPLOYER, ASSIGNMENT, Reason.WEATHER, WeatherReason.WIND, "강풍"))
			.willReturn(new WorkChangeRecorder.RecordedCancellation(change, "GMT+9"));
		new WorkCancellationService(mocked).cancel(EMPLOYER, ASSIGNMENT, new WorkCancellationRequest("WEATHER", "WIND", "\n강풍\t"));
		verify(mocked).cancelBeforeStart(EMPLOYER, ASSIGNMENT, Reason.WEATHER, WeatherReason.WIND, "강풍");
	}

	@Test
	void transactionDeclarations() throws Exception {
		var serviceTx = WorkCancellationService.class.getMethod("cancel", long.class, long.class, WorkCancellationRequest.class)
			.getAnnotation(Transactional.class);
		var recorderTx = WorkChangeRecorder.class.getMethod("cancelBeforeStart", long.class, long.class, Reason.class,
			WeatherReason.class, String.class).getAnnotation(Transactional.class);
		assertThat(serviceTx.isolation()).isEqualTo(Isolation.READ_COMMITTED);
		assertThat(recorderTx.isolation()).isEqualTo(Isolation.READ_COMMITTED);
		assertThat(serviceTx.readOnly()).isFalse();
	}
}
