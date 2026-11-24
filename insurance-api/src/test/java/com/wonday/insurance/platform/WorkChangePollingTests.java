package com.wonday.insurance.platform;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.wonday.insurance.platform.FarmWorkResponse.*;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class WorkChangePollingTests {

	@Autowired WorkChangePollingService polling;
	@MockitoSpyBean PlatformLinkRepository links;
	@Autowired PlatformWorkEventRepository events;
	@Autowired EntityManager entities;
	@Autowired PlatformTransactionManager transactionManager;
	@MockitoSpyBean PlatformWorkCursorRepository cursors;
	@MockitoBean FarmWorkClient farm;
	private long linkId;
	private static final OffsetDateTime TIME = OffsetDateTime.parse("2026-11-24T07:00:00+09:00");

	@BeforeEach
	void prepare() {
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			events.deleteAllInBatch();
			cursors.deleteAllInBatch();
			links.deleteAllInBatch();
			entities.createQuery("delete from Member").executeUpdate();
			try {
				Class<?> memberType = Class.forName("com.wonday.insurance.member.Member");
				Object member = BeanUtils.instantiateClass(memberType.getDeclaredConstructor());
				ReflectionTestUtils.setField(member,"loginId",UUID.randomUUID().toString());
				ReflectionTestUtils.setField(member,"passwordHash","hash");
				ReflectionTestUtils.setField(member,"name","worker");
				Object role = java.util.Arrays.stream(memberType.getDeclaredField("role").getType().getEnumConstants())
					.filter(value -> ((Enum<?>) value).name().equals("WORKER")).findFirst().orElseThrow();
				ReflectionTestUtils.setField(member,"role",role);
				entities.persist(member);
			} catch (ReflectiveOperationException exception) {
				throw new IllegalStateException(exception);
			}
			long memberId = entities.createQuery("select m.id from Member m", Long.class).getSingleResult();
			linkId = links.saveAndFlush(new PlatformLink(memberId, 123, "farm-platform-v1",
				LocalDateTime.of(2026, 11, 20, 0, 0))).getId();
		});
		when(farm.assignment(eq(123L), anyLong())).thenAnswer(call -> assignment(call.getArgument(1)));
	}

	@Test
	void storesVersionsSnapshotsAndCursorAcrossPagesAndRepeatedDelivery() {
		Change first = change(9001, "1", 7001);
		Change revised = change(9001, "2", 7001);
		when(farm.changes(123, null, 100)).thenReturn(page("c1", true, first));
		when(farm.changes(123, "c1", 100)).thenReturn(page("c2", false, first, revised));
		when(farm.changes(123, "c2", 100)).thenReturn(page("c2", false));
		assertThat(polling.pollPage(linkId)).isTrue();
		assertThat(polling.pollPage(linkId)).isFalse();
		assertThat(polling.pollPage(linkId)).isFalse();
		assertThat(events.count()).isEqualTo(2);
		PlatformWorkEvent stored = events.findBySourceChangeIdAndSourceVersion(9001, "1").orElseThrow();
		assertThat(stored.getOccurredAt()).isEqualTo(LocalDateTime.of(2026, 11, 23, 22, 0));
		assertThat(stored.getSourcePayload()).contains("weatherReasonCode", "recordedAt");
		assertThat(stored.getWorkSnapshot()).contains("GMT+9", "120000", "agreedWorkDate");
		assertThat(cursors.findById(123L).orElseThrow().getCursor()).isEqualTo("c2");
		verify(farm, times(2)).assignment(123, 7001);
	}

	@Test
	void detailFailureRollsBackWholePageAndRetriesPreviousCursor() {
		when(farm.changes(123, null, 100)).thenReturn(page("c1", true, change(9001,"1",7001)));
		polling.pollPage(linkId);
		when(farm.changes(123, "c1", 100)).thenReturn(page("c2", false,
			change(9002,"1",7002), change(9003,"1",7003)));
		when(farm.assignment(123,7003)).thenThrow(new IllegalStateException("temporary failure"));
		assertThatThrownBy(() -> polling.pollPage(linkId)).isInstanceOf(IllegalStateException.class);
		assertThat(events.count()).isEqualTo(1);
		assertThat(cursors.findById(123L).orElseThrow().getCursor()).isEqualTo("c1");
		doReturn(assignment(7003)).when(farm).assignment(123, 7003);
		polling.pollPage(linkId);
		assertThat(events.count()).isEqualTo(3);
		assertThat(cursors.findById(123L).orElseThrow().getCursor()).isEqualTo("c2");
		verify(farm, times(2)).changes(123,"c1",100);
	}

	@Test
	void cursorSaveFailureAlsoRollsBackEvents() {
		when(farm.changes(123,null,100)).thenReturn(page("c1",false,change(9001,"1",7001)));
		doThrow(new IllegalStateException("storage failure")).when(cursors).saveAndFlush(any());
		assertThatThrownBy(() -> polling.pollPage(linkId)).isInstanceOf(IllegalStateException.class);
		assertThat(events.count()).isZero();
		assertThat(cursors.findById(123L)).isEmpty();
		reset(cursors);
		polling.pollPage(linkId);
		assertThat(events.count()).isEqualTo(1);
		assertThat(cursors.findById(123L).orElseThrow().getCursor()).isEqualTo("c1");
	}

	@Test
	void listFailureDoesNotCreateCursorAndNextCallRetriesFromBeginning() {
		when(farm.changes(123,null,100)).thenThrow(new IllegalStateException("timeout"))
			.thenReturn(page("empty",false));
		assertThatThrownBy(() -> polling.pollPage(linkId)).isInstanceOf(IllegalStateException.class);
		assertThat(cursors.count()).isZero();
		polling.pollPage(linkId);
		assertThat(cursors.findById(123L).orElseThrow().getCursor()).isEqualTo("empty");
	}

	@Test
	void immutableVersionConflictDoesNotAdvanceCursor() {
		Change original = change(9001, "1", 7001);
		when(farm.changes(123, null, 100)).thenReturn(page("c1", false, original));
		polling.pollPage(linkId);
		Change conflicting = new Change(original.changeId(), original.sourceVersion(), original.assignmentId(),
			original.changeType(), original.reasonCode(), original.weatherReasonCode(), "changed without version",
			original.effectiveAt(), original.recordedAt(), original.updatedAt());
		when(farm.changes(123, "c1", 100)).thenReturn(page("c2", false, conflicting));
		assertThatThrownBy(() -> polling.pollPage(linkId)).isInstanceOf(PlatformVerificationException.class);
		assertThat(cursors.findById(123L).orElseThrow().getCursor()).isEqualTo("c1");
		assertThat(events.count()).isEqualTo(1);
	}

	@Test
	void legacyDuplicateComparesUtcMicrosAndKeepsUnknownOriginalFieldsUninitialized() {
		Change original = new Change(9001L, "1", 7001L, ChangeType.CANCELLED, ReasonCode.WEATHER,
			WeatherReasonCode.RAIN, null, TIME.plusNanos(123456000), TIME, TIME.plusNanos(123456000));
		long eventId = saveLegacyEvent(original);
		Change duplicate = new Change(original.changeId(), original.sourceVersion(), original.assignmentId(),
			original.changeType(), original.reasonCode(), WeatherReasonCode.WIND, original.reasonDetail(),
			original.effectiveAt().withOffsetSameInstant(ZoneOffset.UTC).plusNanos(999), TIME.minusHours(1),
			original.updatedAt().withOffsetSameInstant(ZoneOffset.UTC).plusNanos(999));
		when(farm.changes(123, "c1", 100)).thenReturn(page("c2", false, duplicate));

		polling.pollPage(linkId);

		assertThat(events.count()).isEqualTo(1);
		PlatformWorkEvent stored = events.findById(eventId).orElseThrow();
		assertThat(stored.getSourcePayload()).isNull();
		assertThat(stored.getWorkSnapshot()).isNull();
		assertThat(stored.getReasonDetail()).isNull();
		assertThat(stored.getOccurredAt()).isEqualTo(LocalDateTime.of(2026, 11, 23, 22, 0, 0, 123456000));
		assertThat(cursors.findById(123L).orElseThrow().getCursor()).isEqualTo("c2");
		verify(farm, never()).assignment(anyLong(), anyLong());
	}

	@ParameterizedTest
	@ValueSource(strings = {"eventType", "reasonCode", "reasonDetail", "nullReasonDetail", "occurredAt", "sourceUpdatedAt"})
	void legacyColumnConflictRollsBackPageAndPreservesCursor(String field) {
		Change original = change(9001, "1", 7001);
		long eventId = saveLegacyEvent(original);
		Object conflictingValue = switch (field) {
			case "eventType" -> "EARLY_ENDED";
			case "reasonCode" -> "FARM_CIRCUMSTANCES";
			case "reasonDetail" -> "different explanation";
			case "nullReasonDetail" -> null;
			case "occurredAt", "sourceUpdatedAt" -> LocalDateTime.of(2026, 11, 23, 22, 0, 1);
			default -> throw new IllegalArgumentException(field);
		};
		String storedField = field.equals("nullReasonDetail") ? "reasonDetail" : field;
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			PlatformWorkEvent stored = events.findById(eventId).orElseThrow();
			ReflectionTestUtils.setField(stored, storedField, conflictingValue);
		});
		when(farm.changes(123, "c1", 100)).thenReturn(page("c2", false, change(9002, "1", 7002), original));

		assertThatThrownBy(() -> polling.pollPage(linkId)).isInstanceOf(PlatformVerificationException.class);

		assertThat(events.count()).isEqualTo(1);
		PlatformWorkEvent stored = events.findById(eventId).orElseThrow();
		assertThat(ReflectionTestUtils.getField(stored, storedField)).isEqualTo(conflictingValue);
		assertThat(stored.getSourcePayload()).isNull();
		assertThat(stored.getWorkSnapshot()).isNull();
		assertThat(cursors.findById(123L).orElseThrow().getCursor()).isEqualTo("c1");
	}

	@ParameterizedTest
	@EnumSource(ChangeType.class)
	void collectsFarmCircumstancesAndEarlyEndWithoutCoverageFiltering(ChangeType type) {
		Change change = new Change(9001L, "1", 7001L, type, ReasonCode.FARM_CIRCUMSTANCES,
			null, null, TIME.minusDays(1), TIME, TIME);
		when(farm.changes(123, null, 100)).thenReturn(page("c1", false, change));
		polling.pollPage(linkId);
		PlatformWorkEvent event = events.findBySourceChangeIdAndSourceVersion(9001, "1").orElseThrow();
		assertThat(event.getEventType()).isEqualTo(type.name());
		assertThat(event.getReasonCode()).isEqualTo("FARM_CIRCUMSTANCES");
	}

	@Test
	void revocationStopsPollingAndRenewedConsentKeepsWorkerCursor() {
		when(farm.changes(123,null,100)).thenReturn(page("c1",false));
		polling.pollPage(linkId);
		long renewedId = new TransactionTemplate(transactionManager).execute(status -> {
			PlatformLink old = links.findById(linkId).orElseThrow();
			old.revoke(LocalDateTime.of(2026,11,24,0,0));
			links.flush();
			return links.saveAndFlush(new PlatformLink(old.getMemberId(),123,"v2",
				LocalDateTime.of(2026,11,24,0,0))).getId();
		});
		clearInvocations(farm);
		assertThat(polling.pollPage(linkId)).isFalse();
		verifyNoInteractions(farm);
		when(farm.changes(123,"c1",100)).thenReturn(page("c2",false));
		polling.pollPage(renewedId);
		verify(farm).changes(123,"c1",100);
	}

	@Test
	void concurrentPollersSerializeOnLinkAndUseCommittedCursor() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch secondAtLock = new CountDownLatch(1);
		AtomicInteger lockCalls = new AtomicInteger();
		Answer<?> repositoryAnswer = mockingDetails(links).getMockCreationSettings().getDefaultAnswer();
		doAnswer(invocation -> {
			if (lockCalls.incrementAndGet() == 2) {
				secondAtLock.countDown();
			}
			return repositoryAnswer.answer(invocation);
		}).when(links).lockPollingLink(linkId);
		when(farm.changes(123,null,100)).thenAnswer(call -> {
			entered.countDown();
			assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
			return page("c1",false,change(9001,"1",7001));
		});
		when(farm.changes(123,"c1",100)).thenReturn(page("c2",false,change(9001,"1",7001)));
		try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
			try {
				Future<Boolean> first = pool.submit(() -> polling.pollPage(linkId));
				assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
				Future<Boolean> second = pool.submit(() -> polling.pollPage(linkId));
				assertThat(secondAtLock.await(10, TimeUnit.SECONDS)).isTrue();
				assertThatThrownBy(() -> second.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
				verify(farm, times(1)).changes(eq(123L), nullable(String.class), eq(100));
				release.countDown();
				first.get(15, TimeUnit.SECONDS);
				second.get(15, TimeUnit.SECONDS);
			} finally {
				release.countDown();
			}
		}
		assertThat(events.count()).isEqualTo(1);
		assertThat(cursors.findById(123L).orElseThrow().getCursor()).isEqualTo("c2");
		verify(farm,times(1)).changes(123,null,100);
	}

	private long saveLegacyEvent(Change change) {
		return new TransactionTemplate(transactionManager).execute(status -> {
			LocalDateTime now = LocalDateTime.of(2026, 11, 24, 0, 0);
			PlatformWorkCursor cursor = new PlatformWorkCursor(123, now);
			cursor.advance("c1", now);
			cursors.saveAndFlush(cursor);
			return events.saveAndFlush(new PlatformWorkEvent(linkId, change, null, null, now)).getId();
		});
	}

	static Change change(long id, String version, long assignmentId) {
		return new Change(id,version,assignmentId,ChangeType.CANCELLED,ReasonCode.WEATHER,
			WeatherReasonCode.RAIN,"rain",TIME,TIME,TIME);
	}

	static Page page(String cursor, boolean more, Change... changes) {
		return new Page(123L,List.of(changes),cursor,more);
	}

	static Assignment assignment(long id) {
		return new Assignment(id,123L,TIME.minusDays(3),LocalDate.of(2026,11,24),"GMT+9",
			"farm","CABBAGE","HARVEST",TIME.plusHours(1),TIME.plusHours(10),
			TIME.plusHours(5),TIME.plusHours(6),120000L);
	}
}
