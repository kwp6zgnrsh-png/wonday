package com.wonday.farm.work.service;

import static com.wonday.farm.work.testsupport.SelectionFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import com.wonday.farm.job.entity.WorkSchedule;
import com.wonday.farm.work.exception.WorkSelectionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("WORK-02 SelectionTerms 상태·버전·시작·조건·좌표·조건 원문")
class SelectionTermsTest {
	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static WorkSchedule open() { return schedule(posting(2), WorkSchedule.Status.OPEN, 3); }
	private static void conflict(Runnable call, String code) {
		assertThatThrownBy(call::run).isInstanceOfSatisfying(WorkSelectionException.class, e -> {
			assertThat(e.status().value()).isEqualTo(409);
			assertThat(e.code()).isEqualTo(code);
		});
	}

	@ParameterizedTest
	@EnumSource(WorkSchedule.Status.class)
	@DisplayName("OPEN·PAUSED만 허용, CLOSED·REMOVED는 SELECTION_NOT_ALLOWED")
	void state(WorkSchedule.Status status) {
		var schedule = open();
		ReflectionTestUtils.setField(schedule, "status", status);
		if (status == WorkSchedule.Status.OPEN || status == WorkSchedule.Status.PAUSED) {
			assertThatCode(() -> SelectionTerms.requireState(schedule)).doesNotThrowAnyException();
		} else conflict(() -> SelectionTerms.requireState(schedule), "SELECTION_NOT_ALLOWED");
	}

	@Test
	@DisplayName("버전 일치만 허용, 다르면 TERMS_VERSION_CONFLICT")
	void version() {
		var schedule = open();
		assertThatCode(() -> SelectionTerms.requireVersion(schedule, 3)).doesNotThrowAnyException();
		conflict(() -> SelectionTerms.requireVersion(schedule, 2), "TERMS_VERSION_CONFLICT");
		conflict(() -> SelectionTerms.requireVersion(schedule, 4), "TERMS_VERSION_CONFLICT");
	}

	@Test
	@DisplayName("시작 경계는 작업지 시간대 기준: 1µs 전 허용, 같은 시각·이후 WORK_ALREADY_STARTED, Asia/Seoul도 동일")
	void beforeStart() {
		var schedule = open();
		assertThatCode(() -> SelectionTerms.requireBeforeStart(schedule, START.minusNanos(1000))).doesNotThrowAnyException();
		conflict(() -> SelectionTerms.requireBeforeStart(schedule, START), "WORK_ALREADY_STARTED");
		conflict(() -> SelectionTerms.requireBeforeStart(schedule, START.plusNanos(1000)), "WORK_ALREADY_STARTED");
		ReflectionTestUtils.setField(schedule.getJobPosting(), "timeZone", "Asia/Seoul");
		conflict(() -> SelectionTerms.requireBeforeStart(schedule, START), "WORK_ALREADY_STARTED");
	}

	@Test
	@DisplayName("시간대 없음·공백·잘못된 ID·시작 시각 없음 → WORK_TERMS_UNAVAILABLE")
	void zoneAndStart() {
		for (String zone : new String[] {null, " ", "Mars/Base"}) {
			var schedule = open();
			ReflectionTestUtils.setField(schedule.getJobPosting(), "timeZone", zone);
			conflict(() -> SelectionTerms.requireBeforeStart(schedule, NOW), "WORK_TERMS_UNAVAILABLE");
		}
		var schedule = open();
		ReflectionTestUtils.setField(schedule, "plannedStartAt", null);
		conflict(() -> SelectionTerms.requireBeforeStart(schedule, NOW), "WORK_TERMS_UNAVAILABLE");
	}

	@Test
	@DisplayName("약정 조건 누락(작업지·작목·작업 유형·일급·정원·종료 시각) → WORK_TERMS_UNAVAILABLE, 정상은 통과")
	void conditions() {
		assertThatCode(() -> SelectionTerms.requireConditions(open())).doesNotThrowAnyException();
		for (var field : new String[] {"workLocation", "cropCode", "workTypeCode"}) {
			var schedule = open();
			ReflectionTestUtils.setField(schedule.getJobPosting(), field, " ");
			conflict(() -> SelectionTerms.requireConditions(schedule), "WORK_TERMS_UNAVAILABLE");
		}
		var wage = open();
		ReflectionTestUtils.setField(wage.getJobPosting(), "dailyWage", 0L);
		conflict(() -> SelectionTerms.requireConditions(wage), "WORK_TERMS_UNAVAILABLE");
		var headcount = open();
		ReflectionTestUtils.setField(headcount.getJobPosting(), "headcount", 0);
		conflict(() -> SelectionTerms.requireConditions(headcount), "WORK_TERMS_UNAVAILABLE");
		var end = open();
		ReflectionTestUtils.setField(end, "plannedEndAt", null);
		conflict(() -> SelectionTerms.requireConditions(end), "WORK_TERMS_UNAVAILABLE");
	}

	@Test
	@DisplayName("좌표 없음·범위 밖 → WORK_LOCATION_INCOMPLETE, 경계값 90/180 허용")
	void coordinates() {
		assertThatCode(() -> SelectionTerms.requireCoordinates(open())).doesNotThrowAnyException();
		var edge = open();
		ReflectionTestUtils.setField(edge.getJobPosting(), "latitude", new BigDecimal("-90"));
		ReflectionTestUtils.setField(edge.getJobPosting(), "longitude", new BigDecimal("180"));
		assertThatCode(() -> SelectionTerms.requireCoordinates(edge)).doesNotThrowAnyException();
		for (var values : new BigDecimal[][] {{null, BigDecimal.ONE}, {BigDecimal.ONE, null}, {new BigDecimal("90.000001"), BigDecimal.ONE},
				{BigDecimal.ONE, new BigDecimal("-180.000001")}}) {
			var schedule = open();
			ReflectionTestUtils.setField(schedule.getJobPosting(), "latitude", values[0]);
			ReflectionTestUtils.setField(schedule.getJobPosting(), "longitude", values[1]);
			conflict(() -> SelectionTerms.requireCoordinates(schedule), "WORK_LOCATION_INCOMPLETE");
		}
	}

	@Test
	@DisplayName("조건 원문 JSON: 버전·날짜·시간대·주소·좌표·작목·작업 유형·작업 내용·정원·일급·근무/휴게 시각 포함, 휴게 없음은 null")
	void snapshot() {
		var json = JSON.readTree(SelectionTerms.snapshot(open(), JSON));
		assertThat(json.get("termsVersion").asInt()).isEqualTo(3);
		assertThat(json.get("workDate").asString()).isEqualTo("2026-12-10");
		assertThat(json.get("timeZone").asString()).isEqualTo("GMT+9");
		assertThat(json.get("workLocation").asString()).isEqualTo("가상 작업지 1");
		assertThat(json.get("latitude").decimalValue()).isEqualByComparingTo("37.123456");
		assertThat(json.get("longitude").decimalValue()).isEqualByComparingTo("127.654321");
		assertThat(json.get("cropCode").asString()).isEqualTo("FC03030100030103");
		assertThat(json.get("workTypeCode").asString()).isEqualTo("0003");
		assertThat(json.get("description").asString()).isEqualTo("가상 작업 내용");
		assertThat(json.get("headcount").asInt()).isEqualTo(2);
		assertThat(json.get("dailyWage").asLong()).isEqualTo(120000L);
		assertThat(json.get("plannedStartAt").asString()).isEqualTo("2026-12-10T09:00");
		assertThat(json.get("plannedEndAt").asString()).isEqualTo("2026-12-10T17:00");
		assertThat(json.get("breakStartAt").asString()).isEqualTo("2026-12-10T12:00");
		assertThat(json.get("breakEndAt").asString()).isEqualTo("2026-12-10T13:00");
		var noBreak = open();
		ReflectionTestUtils.setField(noBreak, "breakStartAt", null);
		ReflectionTestUtils.setField(noBreak, "breakEndAt", null);
		var other = JSON.readTree(SelectionTerms.snapshot(noBreak, JSON));
		assertThat(other.get("breakStartAt").isNull()).isTrue();
		assertThat(other.get("breakEndAt").isNull()).isTrue();
	}
}
