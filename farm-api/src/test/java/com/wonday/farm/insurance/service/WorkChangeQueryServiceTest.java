package com.wonday.farm.insurance.service;

import static com.wonday.farm.insurance.testsupport.InsuranceFixtures.change;
import static com.wonday.farm.insurance.testsupport.InsuranceFixtures.member;
import static com.wonday.farm.insurance.testsupport.InsuranceFixtures.stream;
import static com.wonday.farm.insurance.testsupport.InsuranceFixtures.version;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.LongStream;

import com.wonday.farm.change.entity.WorkChange;
import com.wonday.farm.change.entity.WorkChange.Reason;
import com.wonday.farm.change.entity.WorkChange.Type;
import com.wonday.farm.change.entity.WorkChange.WeatherReason;
import com.wonday.farm.insurance.dto.WorkChangesResponse;
import com.wonday.farm.insurance.entity.ChangeCursor;
import com.wonday.farm.insurance.entity.ChangeStream;
import com.wonday.farm.insurance.entity.WorkChangeVersion;
import com.wonday.farm.insurance.exception.InsuranceApiException;
import com.wonday.farm.insurance.repository.ChangeCursorRepository;
import com.wonday.farm.insurance.repository.ChangeStreamRepository;
import com.wonday.farm.insurance.repository.WorkChangeVersionRepository;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

// 변경 목록 폴링과 커서. Repository는 mock이므로 실제 MySQL에서의 sequence 정렬·잠금 대기·커밋 역전 시
// 누락 없음·커서 유니크 제약은 증명하지 않는다 (통합 검증 대상). 서비스가 커서를 어떻게 해석·발급하고
// 저장된 버전 행을 어떻게 응답으로 바꾸는지를 검증한다.
class WorkChangeQueryServiceTest {
	private static final long WORKER_ID = 5L;

	private final MemberRepository members = mock(MemberRepository.class);
	private final ChangeStreamRepository streams = mock(ChangeStreamRepository.class);
	private final ChangeCursorRepository cursors = mock(ChangeCursorRepository.class);
	private final WorkChangeVersionRepository versions = mock(WorkChangeVersionRepository.class);
	private final WorkChangeQueryService service =
		new WorkChangeQueryService(new InsuranceDataAccess(members), streams, cursors, versions);
	private ChangeStream stream;

	@BeforeEach
	void workerWithStreamExists() {
		given(members.findById(WORKER_ID)).willReturn(Optional.of(member(WORKER_ID, MemberRole.WORKER)));
		stream = stream(WORKER_ID, 10);
		given(streams.lockWorker(WORKER_ID)).willReturn(Optional.of(stream));
		given(cursors.findByWorkerIdAndAfterSequence(anyLong(), anyLong())).willReturn(Optional.empty());
		given(cursors.save(any())).willAnswer(invocation -> invocation.getArgument(0));
		given(versions.findByWorkerIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(anyLong(), anyLong(), any()))
			.willReturn(List.of());
	}

	@Nested
	class Limit {
		@ParameterizedTest(name = "limit={0}")
		@ValueSource(ints = {1, 100, 500})
		void acceptedLimitReadsOneExtraRowToDetectMore(int limit) {
			service.poll(WORKER_ID, null, limit);

			verify(versions).findByWorkerIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(WORKER_ID, 0, PageRequest.of(0, limit + 1));
		}

		@ParameterizedTest(name = "limit={0}")
		@ValueSource(ints = {0, -1, 501, Integer.MAX_VALUE, Integer.MIN_VALUE})
		void outOfRangeLimitIsInvalidBeforeAnyLookup(int limit) {
			assertError(() -> service.poll(WORKER_ID, null, limit), HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
			verifyNoInteractions(members, streams, cursors, versions);
		}
	}

	@Nested
	class Worker {
		@Test
		void nonWorkerIsNotFoundAndNoStreamIsCreated() {
			given(members.findById(WORKER_ID)).willReturn(Optional.of(member(WORKER_ID, MemberRole.EMPLOYER)));

			assertError(() -> service.poll(WORKER_ID, null, 100), HttpStatus.NOT_FOUND, "WORKER_NOT_FOUND");
			verifyNoInteractions(streams, cursors, versions);
		}

		@Test
		void nonPositiveWorkerIdIsInvalid() {
			assertError(() -> service.poll(0, null, 100), HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
			verifyNoInteractions(streams);
		}

		@Test
		void locksTheWorkerStreamBeforeReadingCursorOrVersions() {
			String token = existingCursor(WORKER_ID, 3);

			service.poll(WORKER_ID, token, 100);

			var order = inOrder(streams, cursors, versions);
			order.verify(streams).ensureExists(WORKER_ID);
			order.verify(streams).lockWorker(WORKER_ID);
			order.verify(cursors).findById(token);
			order.verify(versions).findByWorkerIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(eq(WORKER_ID), eq(3L), any());
		}

		@Test
		void missingStreamAfterEnsureIsUnavailable() {
			given(streams.lockWorker(WORKER_ID)).willReturn(Optional.empty());

			assertError(() -> service.poll(WORKER_ID, null, 100), HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE");
		}

		@Test
		void isReadCommittedAndNotReadOnlyBecauseCursorsArePersisted() throws Exception {
			var poll = WorkChangeQueryService.class.getMethod("poll", long.class, String.class, int.class);
			var transactional = poll.getAnnotation(Transactional.class);

			assertThat(transactional.readOnly()).isFalse();
			assertThat(transactional.isolation()).isEqualTo(Isolation.READ_COMMITTED);
		}
	}

	@Nested
	class FirstRequest {
		@Test
		void withoutCursorReadsFromTheBeginning() {
			service.poll(WORKER_ID, null, 100);

			verify(versions).findByWorkerIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(eq(WORKER_ID), eq(0L), any());
		}

		@Test
		void emptyResultStillReturnsACursorForPositionZero() {
			var response = service.poll(WORKER_ID, null, 100);

			assertThat(response.workerId()).isEqualTo(WORKER_ID);
			assertThat(response.items()).isEmpty();
			assertThat(response.hasMore()).isFalse();
			assertThat(response.nextCursor()).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
			ChangeCursor saved = savedCursor();
			assertThat(saved.getToken()).isEqualTo(response.nextCursor());
			assertThat(saved.getWorkerId()).isEqualTo(WORKER_ID);
			assertThat(saved.getAfterSequence()).isZero();
		}

		@Test
		void existingCursorForSameWorkerAndPositionIsReusedInsteadOfCreatingANewOne() {
			var reused = new ChangeCursor(WORKER_ID, 0);
			given(cursors.findByWorkerIdAndAfterSequence(WORKER_ID, 0)).willReturn(Optional.of(reused));

			var response = service.poll(WORKER_ID, null, 100);

			assertThat(response.nextCursor()).isEqualTo(reused.getToken());
			verify(cursors, never()).save(any());
		}
	}

	@Nested
	class Paging {
		@Test
		void returnsAtMostLimitItemsAndReportsHasMore() {
			givenVersionsAfter(0, versions(1, 2, 3));

			var response = service.poll(WORKER_ID, null, 2);

			assertThat(response.items()).extracting(WorkChangesResponse.Item::changeId).containsExactly(101L, 102L);
			assertThat(response.hasMore()).isTrue();
			assertThat(savedCursor().getAfterSequence()).isEqualTo(2);
		}

		@Test
		void exactlyLimitItemsMeansNoMore() {
			givenVersionsAfter(0, versions(1, 2));

			var response = service.poll(WORKER_ID, null, 2);

			assertThat(response.items()).hasSize(2);
			assertThat(response.hasMore()).isFalse();
		}

		@Test
		void nextPositionIsTheLastReturnedSequenceEvenWithGaps() {
			// 다른 근로자 버전이 섞이지 않으므로 이 근로자의 sequence는 연속이지만, 서비스는 연속성을 가정하지 않는다.
			givenVersionsAfter(0, versions(2, 7, 9));

			service.poll(WORKER_ID, null, 100);

			assertThat(savedCursor().getAfterSequence()).isEqualTo(9);
		}

		@Test
		void cursorContinuesFromItsStoredPosition() {
			String token = existingCursor(WORKER_ID, 2);
			givenVersionsAfter(2, versions(3, 4));

			var response = service.poll(WORKER_ID, token, 100);

			assertThat(response.items()).extracting(WorkChangesResponse.Item::changeId).containsExactly(103L, 104L);
			assertThat(savedCursor().getAfterSequence()).isEqualTo(4);
		}

		@Test
		void fiveHundredRowsWithMoreBehind() {
			givenVersionsAfter(0, versions(LongStream.rangeClosed(1, 501).toArray()));
			ReflectionTestUtils.setField(stream, "lastSequence", 501L);

			var response = service.poll(WORKER_ID, null, 500);

			assertThat(response.items()).hasSize(500);
			assertThat(response.hasMore()).isTrue();
			assertThat(savedCursor().getAfterSequence()).isEqualTo(500);
		}

		@Test
		void emptyPageKeepsThePositionAndLaterRecordIsReturnedFromTheSameCursor() {
			// 순서가 있는 mock 응답으로 '빈 폴링 → 새 기록 → 같은 커서로 재조회'를 흉내 낸다.
			// 실제 DB에서 커밋 역전이 있어도 누락되지 않는다는 증거는 아니다.
			var cursorAt3 = new ChangeCursor(WORKER_ID, 3);
			given(cursors.findById(cursorAt3.getToken())).willReturn(Optional.of(cursorAt3));
			given(cursors.findByWorkerIdAndAfterSequence(WORKER_ID, 3)).willReturn(Optional.of(cursorAt3));
			ReflectionTestUtils.setField(stream, "lastSequence", 3L);
			given(versions.findByWorkerIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(eq(WORKER_ID), eq(3L), any()))
				.willReturn(List.of())
				.willReturn(versions(4));

			var empty = service.poll(WORKER_ID, cursorAt3.getToken(), 100);
			ReflectionTestUtils.setField(stream, "lastSequence", 4L);
			var next = service.poll(WORKER_ID, empty.nextCursor(), 100);

			assertThat(empty.items()).isEmpty();
			assertThat(empty.nextCursor()).isEqualTo(cursorAt3.getToken());
			assertThat(next.items()).extracting(WorkChangesResponse.Item::changeId).containsExactly(104L);
		}

		@Test
		void sameCursorReturnsTheSameVersionContentAfterTheChangeWasRevised() {
			// 응답은 work_change(현재 상태)가 아니라 버전 행에서 만든다. v1 이후 사유가 보완돼도 v1 응답은 그대로다.
			var current = change(500L, 31L, Type.CANCELLED, Reason.WEATHER, WeatherReason.RAIN, "비",
				LocalDateTime.parse("2026-11-29T22:00"), LocalDateTime.parse("2026-11-29T22:00"));
			var v1 = version(WORKER_ID, 1, current);
			current.revise(Reason.FARM_CIRCUMSTANCES, null, "정정", LocalDateTime.parse("2026-11-30T00:00"));
			var v2 = version(WORKER_ID, 2, current);
			givenVersionsAfter(0, List.of(v1, v2));

			var first = service.poll(WORKER_ID, null, 100);
			var again = service.poll(WORKER_ID, null, 100);

			assertThat(first.items()).extracting(WorkChangesResponse.Item::sourceVersion).containsExactly("1", "2");
			assertThat(first.items().get(0).reasonCode()).isEqualTo(Reason.WEATHER);
			assertThat(first.items().get(0).weatherReasonCode()).isEqualTo(WeatherReason.RAIN);
			assertThat(first.items().get(0).reasonDetail()).isEqualTo("비");
			assertThat(first.items().get(1).reasonCode()).isEqualTo(Reason.FARM_CIRCUMSTANCES);
			assertThat(again.items()).isEqualTo(first.items());
		}
	}

	@Nested
	class InvalidCursor {
		@ParameterizedTest(name = "cursor=\"{0}\"")
		@ValueSource(strings = {"", " ", "abc", "1", "0-0-0-0-0", "ABCDEF12-3456-7890-ABCD-EF1234567890",
			"abcdef12-3456-7890-abcd-ef1234567890 ", "abcdef12345678901234567890abcdef",
			"abcdef12-3456-7890-abcd-ef1234567890' or '1'='1"})
		void malformedCursorIsRejectedWithoutLookup(String cursor) {
			assertInvalidCursor(cursor);
			verify(cursors, never()).findById(any());
		}

		@Test
		void unknownCursorIsRejected() {
			given(cursors.findById(any())).willReturn(Optional.empty());

			assertInvalidCursor(UUID.randomUUID().toString());
		}

		@Test
		void anotherWorkersCursorIsRejected() {
			String token = existingCursor(6L, 0);

			assertInvalidCursor(token);
			verify(versions, never()).findByWorkerIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(anyLong(), anyLong(), any());
		}

		@Test
		void cursorBeyondTheStreamsLastSequenceIsRejected() {
			assertInvalidCursor(existingCursor(WORKER_ID, 11));
		}

		@Test
		void cursorAtTheLastSequenceIsAccepted() {
			var response = service.poll(WORKER_ID, existingCursor(WORKER_ID, 10), 100);

			assertThat(response.items()).isEmpty();
		}

		private void assertInvalidCursor(String cursor) {
			assertError(() -> service.poll(WORKER_ID, cursor, 100), HttpStatus.BAD_REQUEST, "INVALID_CURSOR");
			verify(cursors, never()).save(any());
		}
	}

	@Nested
	class ItemMapping {
		@Test
		void mapsVersionFieldsAndShowsUtcTimesInPlusNine() {
			var cancelled = change(201L, 31L, Type.CANCELLED, Reason.WEATHER, WeatherReason.WIND, "강풍",
				LocalDateTime.parse("2026-11-29T15:00"), LocalDateTime.parse("2026-11-29T15:00:00.000001"));
			cancelled.revise(Reason.WEATHER, WeatherReason.WIND, "강풍 정정", LocalDateTime.parse("2026-11-29T16:30"));
			givenVersionsAfter(0, List.of(version(WORKER_ID, 1, cancelled)));

			var item = service.poll(WORKER_ID, null, 100).items().getFirst();

			assertThat(item.changeId()).isEqualTo(201L);
			assertThat(item.sourceVersion()).isEqualTo("2");
			assertThat(item.assignmentId()).isEqualTo(31L);
			assertThat(item.changeType()).isEqualTo(Type.CANCELLED);
			assertThat(item.reasonCode()).isEqualTo(Reason.WEATHER);
			assertThat(item.weatherReasonCode()).isEqualTo(WeatherReason.WIND);
			assertThat(item.reasonDetail()).isEqualTo("강풍 정정");
			assertThat(item.effectiveAt()).isEqualTo(OffsetDateTime.parse("2026-11-30T00:00+09:00"));
			assertThat(item.recordedAt()).isEqualTo(OffsetDateTime.parse("2026-11-30T00:00:00.000001+09:00"));
			assertThat(item.updatedAt()).isEqualTo(OffsetDateTime.parse("2026-11-30T01:30+09:00"));
			assertThat(item.effectiveAt().getOffset().getTotalSeconds()).isEqualTo(9 * 3600);
		}

		@Test
		void returnsEveryStoredTypeAndReasonWithoutFiltering() {
			// 농장 사정·조기 종료·기타 사유도 제외하지 않는다. 보험 심사 제외 판단은 보험 서버 역할이다.
			List<WorkChangeVersion> all = new ArrayList<>();
			long sequence = 1;
			for (Type type : Type.values()) {
				for (Reason reason : Reason.values()) {
					var value = change(1000 + sequence, 2000 + sequence, type, reason,
						reason == Reason.WEATHER ? WeatherReason.OTHER : null, null,
						LocalDateTime.parse("2026-11-29T00:00"), LocalDateTime.parse("2026-11-29T00:00"));
					all.add(version(WORKER_ID, sequence++, value));
				}
			}
			givenVersionsAfter(0, all);

			var items = service.poll(WORKER_ID, null, 100).items();

			assertThat(items).hasSize(Type.values().length * Reason.values().length);
			assertThat(items).extracting(WorkChangesResponse.Item::changeType).containsOnly(Type.values());
			assertThat(items).extracting(WorkChangesResponse.Item::reasonCode).containsOnly(Reason.values());
		}

		@Test
		void invalidStoredEventTimeZoneIsUnavailable() {
			var value = new WorkChangeVersion(WORKER_ID, 1, change(1L, 2L, Type.CANCELLED, Reason.OTHER, null, null,
				LocalDateTime.parse("2026-11-29T00:00"), LocalDateTime.parse("2026-11-29T00:00")), "Seoul");
			givenVersionsAfter(0, List.of(value));

			assertError(() -> service.poll(WORKER_ID, null, 100), HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE");
		}
	}

	private String existingCursor(long workerId, long after) {
		var cursor = new ChangeCursor(workerId, after);
		given(cursors.findById(cursor.getToken())).willReturn(Optional.of(cursor));
		return cursor.getToken();
	}

	private void givenVersionsAfter(long after, List<WorkChangeVersion> found) {
		given(versions.findByWorkerIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(eq(WORKER_ID), eq(after), any()))
			.willAnswer(invocation -> {
				PageRequest page = invocation.getArgument(2);
				return found.subList(0, Math.min(found.size(), page.getPageSize()));
			});
	}

	private static List<WorkChangeVersion> versions(long... sequences) {
		List<WorkChangeVersion> found = new ArrayList<>();
		for (long sequence : sequences) {
			WorkChange value = change(100 + sequence, 300 + sequence, Type.CANCELLED, Reason.OTHER, null, null,
				LocalDateTime.parse("2026-11-29T00:00"), LocalDateTime.parse("2026-11-29T00:00"));
			found.add(version(WORKER_ID, sequence, value));
		}
		return found;
	}

	private ChangeCursor savedCursor() {
		var captor = org.mockito.ArgumentCaptor.forClass(ChangeCursor.class);
		verify(cursors).save(captor.capture());
		return captor.getValue();
	}

	private static void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, HttpStatus status,
			String code) {
		assertThatThrownBy(call).isInstanceOfSatisfying(InsuranceApiException.class, error -> {
			assertThat(error.status()).isEqualTo(status);
			assertThat(error.code()).isEqualTo(code);
		});
	}
}
