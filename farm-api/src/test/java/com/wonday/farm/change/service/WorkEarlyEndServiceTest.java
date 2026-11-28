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

import com.wonday.farm.change.dto.WorkEarlyEndRequest;
import com.wonday.farm.change.entity.WorkChange;
import com.wonday.farm.change.entity.WorkChange.Reason;
import com.wonday.farm.change.entity.WorkChange.Type;
import com.wonday.farm.change.entity.WorkChange.WeatherReason;
import com.wonday.farm.change.exception.WorkEarlyEndException;
import com.wonday.farm.change.repository.WorkChangeRepository;
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

// 실제 WorkEarlyEndService + 실제 WorkChangeRecorder, 저장소만 mock. 트랜잭션은 선언만 확인한다.
@DisplayName("CHANGE-03 조기 종료 서비스 (실제 서비스+기록기, 저장소 mock)")
class WorkEarlyEndServiceTest {
	private static final long EMPLOYER = 10L, WORKER = 5L, ASSIGNMENT = 31L;
	private static final Instant NOW = Instant.parse("2026-11-30T09:00:00.123456789Z");
	private final MemberRepository members = mock(MemberRepository.class);
	private final WorkAssignmentRepository assignments = mock(WorkAssignmentRepository.class);
	private final WorkChangeRepository changes = mock(WorkChangeRepository.class);
	private final ChangeStreamRepository streams = mock(ChangeStreamRepository.class);
	private final WorkChangeVersionRepository versions = mock(WorkChangeVersionRepository.class);
	private final WorkEarlyEndService service = new WorkEarlyEndService(new WorkChangeRecorder(members, assignments, changes, streams,
		versions, Clock.fixed(NOW, ZoneOffset.UTC)));
	private WorkAssignment stored;

	@BeforeEach
	void owned() {
		given(members.findById(EMPLOYER)).willReturn(Optional.of(member(EMPLOYER, MemberRole.EMPLOYER)));
		given(assignments.findOwnership(ASSIGNMENT)).willReturn(Optional.of(new WorkAssignmentRepository.Ownership() {
			public long getWorkerId() { return WORKER; }
			public long getEmployerId() { return EMPLOYER; }
		}));
		given(streams.lockWorker(WORKER)).willReturn(Optional.of(stream(WORKER, 0)));
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
	void responseSeparatesEffectiveAndRecordedInAgreedOffset() {
		var response = service.end(EMPLOYER, ASSIGNMENT, new WorkEarlyEndRequest(OffsetDateTime.parse("2026-11-30T05:00:00.123456Z"),
			"WEATHER", "RAIN", "  강수로 작업을 중단했습니다.  "));
		assertThat(response.assignmentId()).isEqualTo(ASSIGNMENT);
		assertThat(response.changeId()).isEqualTo(77L);
		assertThat(response.changeType()).isEqualTo(Type.EARLY_ENDED);
		assertThat(response.reasonCode()).isEqualTo(Reason.WEATHER);
		assertThat(response.weatherReasonCode()).isEqualTo(WeatherReason.RAIN);
		assertThat(response.reasonDetail()).isEqualTo("강수로 작업을 중단했습니다.");
		assertThat(response.effectiveAt()).isEqualTo(OffsetDateTime.parse("2026-11-30T14:00:00.123456+09:00"));
		assertThat(response.effectiveAt().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
		assertThat(response.recordedAt()).isEqualTo(OffsetDateTime.parse("2026-11-30T18:00:00.123456+09:00"));
		assertThat(response.updatedAt()).isEqualTo(response.recordedAt());
		assertThat(response.sourceVersion()).isEqualTo("1");
	}

	@Test
	void replayReturnsStoredValues() {
		var previous = change(500L, ASSIGNMENT, Type.EARLY_ENDED, Reason.OTHER, null, null,
			LocalDateTime.parse("2026-11-30T05:00"), LocalDateTime.parse("2026-11-30T09:30:00.000001"));
		ReflectionTestUtils.setField(previous, "updatedAt", LocalDateTime.parse("2026-11-30T10:00:00.000002"));
		ReflectionTestUtils.setField(previous, "sourceVersion", 3L);
		given(changes.findForUpdate(ASSIGNMENT)).willReturn(Optional.of(previous));
		var response = service.end(EMPLOYER, ASSIGNMENT, new WorkEarlyEndRequest(OffsetDateTime.parse("2026-11-30T14:00+09:00"), "OTHER", null, " "));
		assertThat(response.changeId()).isEqualTo(500L);
		assertThat(response.sourceVersion()).isEqualTo("3");
		assertThat(response.weatherReasonCode()).isNull();
		assertThat(response.reasonDetail()).isNull();
		assertThat(response.effectiveAt()).isEqualTo(OffsetDateTime.parse("2026-11-30T14:00+09:00"));
		assertThat(response.recordedAt()).isEqualTo(OffsetDateTime.parse("2026-11-30T18:30:00.000001+09:00"));
		assertThat(response.updatedAt()).isEqualTo(OffsetDateTime.parse("2026-11-30T19:00:00.000002+09:00"));
		verify(changes, never()).saveAndFlush(any());
		verifyNoInteractions(versions);
	}

	@Test
	void replayWithBrokenZoneIs503() {
		given(changes.findForUpdate(ASSIGNMENT)).willReturn(Optional.of(change(500L, ASSIGNMENT, Type.EARLY_ENDED, Reason.OTHER, null, null,
			LocalDateTime.parse("2026-11-30T05:00"), LocalDateTime.parse("2026-11-30T09:30"))));
		ReflectionTestUtils.setField(stored, "agreedTimeZone", "broken/zone");
		assertThatThrownBy(() -> service.end(EMPLOYER, ASSIGNMENT, new WorkEarlyEndRequest(OffsetDateTime.parse("2026-11-30T05:00Z"), "OTHER", null, null)))
			.isInstanceOfSatisfying(InsuranceApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
	}

	@Test
	void saveFailurePropagates() {
		willThrow(new DataAccessResourceFailureException("down")).given(versions).save(any());
		assertThatThrownBy(() -> service.end(EMPLOYER, ASSIGNMENT, new WorkEarlyEndRequest(OffsetDateTime.parse("2026-11-30T05:00Z"), "OTHER", null, null)))
			.isInstanceOf(DataAccessResourceFailureException.class);
	}

	@Test
	void invalidInputNeverReachesRecorder() {
		var recorder = mock(WorkChangeRecorder.class);
		var guarded = new WorkEarlyEndService(recorder);
		var t = OffsetDateTime.parse("2026-11-30T05:00Z");
		for (var call : new org.assertj.core.api.ThrowableAssert.ThrowingCallable[] {
				() -> guarded.end(0, ASSIGNMENT, new WorkEarlyEndRequest(t, "OTHER", null, null)),
				() -> guarded.end(EMPLOYER, -1, new WorkEarlyEndRequest(t, "OTHER", null, null)),
				() -> guarded.end(EMPLOYER, ASSIGNMENT, null),
				() -> guarded.end(EMPLOYER, ASSIGNMENT, new WorkEarlyEndRequest(null, "OTHER", null, null)),
				() -> guarded.end(EMPLOYER, ASSIGNMENT, new WorkEarlyEndRequest(t.plusNanos(1), "OTHER", null, null)),
				() -> guarded.end(EMPLOYER, ASSIGNMENT, new WorkEarlyEndRequest(t, "WEATHER", null, null)),
				() -> guarded.end(EMPLOYER, ASSIGNMENT, new WorkEarlyEndRequest(t, "OTHER", "RAIN", null)),
				() -> guarded.end(EMPLOYER, ASSIGNMENT, new WorkEarlyEndRequest(t, "OTHER", null, "x".repeat(501)))}) {
			assertThatThrownBy(call).isInstanceOfSatisfying(WorkEarlyEndException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
		}
		verifyNoInteractions(recorder);
	}

	@Test
	void normalizedValuesPassedToRecorder() {
		var recorder = mock(WorkChangeRecorder.class);
		var t = OffsetDateTime.parse("2026-11-30T14:00:00.5+09:00");
		given(recorder.endWork(EMPLOYER, ASSIGNMENT, t, Reason.WEATHER, WeatherReason.WIND, "강풍"))
			.willReturn(new WorkChangeRecorder.RecordedEarlyEnd(change(9L, ASSIGNMENT, Type.EARLY_ENDED, Reason.WEATHER, WeatherReason.WIND,
				"강풍", LocalDateTime.parse("2026-11-30T05:00:00.5"), LocalDateTime.parse("2026-11-30T09:00")), "GMT+9"));
		new WorkEarlyEndService(recorder).end(EMPLOYER, ASSIGNMENT, new WorkEarlyEndRequest(t, "WEATHER", "WIND", "\n강풍 "));
		verify(recorder).endWork(EMPLOYER, ASSIGNMENT, t, Reason.WEATHER, WeatherReason.WIND, "강풍");
	}

	@Test
	void transactionDeclarations() throws Exception {
		var serviceTx = WorkEarlyEndService.class.getMethod("end", long.class, long.class, WorkEarlyEndRequest.class).getAnnotation(Transactional.class);
		var recorderTx = WorkChangeRecorder.class.getMethod("endWork", long.class, long.class, OffsetDateTime.class, Reason.class,
			WeatherReason.class, String.class).getAnnotation(Transactional.class);
		assertThat(serviceTx.isolation()).isEqualTo(Isolation.READ_COMMITTED);
		assertThat(recorderTx.isolation()).isEqualTo(Isolation.READ_COMMITTED);
	}
}
