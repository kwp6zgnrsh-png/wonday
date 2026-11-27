package com.wonday.farm.application.service;

import static com.wonday.farm.application.testsupport.MyApplicationRows.CROP;
import static com.wonday.farm.application.testsupport.MyApplicationRows.WORK_TYPE;
import static com.wonday.farm.application.testsupport.MyApplicationRows.assignment;
import static com.wonday.farm.application.testsupport.MyApplicationRows.revision;
import static com.wonday.farm.application.testsupport.MyApplicationRows.row;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

import com.wonday.farm.application.dto.MyApplicationResponses;
import com.wonday.farm.reference.AgriculturalCodeCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

// 실제 매퍼 + 실제 코드 목록(reference/*.json). 입력은 가상 조회 행이며 SQL 결과가 아니다.
@DisplayName("APP-04 조건 비교·응답 변환")
class ApplicationConditionMapperTest {
	private static final List<String> ALL_FIELDS = List.of("workDate", "timeZone", "workLocation", "latitude", "longitude",
		"cropCode", "workTypeCode", "description", "headcount", "dailyWage", "plannedStartAt", "plannedEndAt",
		"breakStartAt", "breakEndAt");
	private final ApplicationConditionMapper mapper = new ApplicationConditionMapper(new AgriculturalCodeCatalog());

	private static OffsetDateTime at(String value) { return OffsetDateTime.parse(value); }

	@Nested
	@DisplayName("목록 항목")
	class Item {
		@Test
		@DisplayName("UTC 지원 시각은 +09:00·마이크로초 유지, legacyAppliedAt=null, 기준 UTC, 현재 여부는 currentFlag 1")
		void utc() {
			var item = mapper.item(row());
			assertThat(item.appliedAt()).isEqualTo(at("2026-12-01T15:00:00.123456+09:00"));
			assertThat(item.appliedAt().getOffset().getTotalSeconds()).isEqualTo(9 * 3600);
			assertThat(item.legacyAppliedAt()).isNull();
			assertThat(item.appliedTimeBasis()).isEqualTo("UTC");
			assertThat(item.current()).isTrue();
			assertThat(item.completedAt()).isNull();
			assertThat(item.assignmentId()).isNull();
		}

		@Test
		@DisplayName("UTC 누락(V12 이전) 행: appliedAt=null, 원본 legacyAppliedAt 보존, LEGACY_LOCAL_UNKNOWN_ZONE")
		void legacy() {
			var item = mapper.item(row(v -> { v.put("appliedAtUtc", null); v.put("legacyAppliedAt", LocalDateTime.parse("2025-05-01T08:30")); }));
			assertThat(item.appliedAt()).isNull();
			assertThat(item.legacyAppliedAt()).isEqualTo(LocalDateTime.parse("2025-05-01T08:30"));
			assertThat(item.appliedTimeBasis()).isEqualTo("LEGACY_LOCAL_UNKNOWN_ZONE");
		}

		@Test
		@DisplayName("과거 회차 currentFlag 0 → current=false")
		void past() {
			assertThat(mapper.item(row(v -> v.put("currentFlag", 0))).current()).isFalse();
		}

		@Test
		@DisplayName("조건 비교 상태: 같은 버전 UNCHANGED, 다른 버전 CHANGED, 지원 버전 null UNKNOWN")
		void comparison() {
			assertThat(mapper.item(row()).conditionComparisonStatus()).isEqualTo("UNCHANGED");
			assertThat(mapper.item(row(v -> v.put("latestTermsVersion", 4))).conditionComparisonStatus()).isEqualTo("CHANGED");
			assertThat(mapper.item(row(v -> v.put("appliedTermsVersion", null))).conditionComparisonStatus()).isEqualTo("UNKNOWN");
		}

		@Test
		@DisplayName("Integer 지원 버전이 캐시 범위(127) 밖이어도 값으로 비교(UNCHANGED)")
		void largeVersion() {
			assertThat(mapper.item(row(v -> { v.put("appliedTermsVersion", Integer.valueOf(1000)); v.put("latestTermsVersion", 1000); }))
				.conditionComparisonStatus()).isEqualTo("UNCHANGED");
		}

		@Test
		@DisplayName("취소 구분: 확정 근무 없이 CANCELLED → SCHEDULE_CANCELLED, 확정 근무 있으면 ASSIGNMENT_CANCELLED")
		void cancellation() {
			assertThat(mapper.item(row(v -> { v.put("displayStatus", "CANCELLED"); v.put("scheduleStatus", "REMOVED"); })).cancellationType())
				.isEqualTo("SCHEDULE_CANCELLED");
			assertThat(mapper.item(row(v -> { assignment(v); v.put("displayStatus", "CANCELLED"); v.put("changeType", "CANCELLED"); }))
				.cancellationType()).isEqualTo("ASSIGNMENT_CANCELLED");
		}

		@ParameterizedTest
		@ValueSource(strings = {"PENDING", "CONFIRMED", "NOT_SELECTED", "EARLY_ENDED", "COMPLETED", "UNKNOWN"})
		@DisplayName("취소가 아닌 표시 상태는 cancellationType=null이고 표시 상태는 SQL 값 그대로(UNKNOWN 포함, 추측 보정 없음)")
		void otherStatuses(String status) {
			var item = mapper.item(row(v -> { assignment(v); v.put("displayStatus", status); }));
			assertThat(item.cancellationType()).isNull();
			assertThat(item.displayStatus()).isEqualTo(status);
			assertThat(item.applicationStatus()).isEqualTo("SELECTED");
		}

		@Test
		@DisplayName("completedAt은 저장된 completed_at_utc만 +09:00으로 변환, 없으면 null")
		void completedAt() {
			assertThat(mapper.item(row(v -> { assignment(v); v.put("completedAtUtc", LocalDateTime.parse("2026-12-10T08:00:00.000009")); }))
				.completedAt()).isEqualTo(at("2026-12-10T17:00:00.000009+09:00"));
			assertThat(mapper.item(row(v -> assignment(v))).completedAt()).isNull();
		}

		@Test
		@DisplayName("목록 근무일·제목은 최신 일정·공고 값")
		void latestValues() {
			var item = mapper.item(row(v -> { revision(v); v.put("revisionWorkDate", LocalDate.parse("2026-12-09")); }));
			assertThat(item.workDate()).isEqualTo(LocalDate.parse("2026-12-10"));
			assertThat(item.title()).isEqualTo("[가상] 콩 경운 작업");
		}
	}


	@Nested
	@DisplayName("지원 당시 조건 선택")
	class Applied {
		@Test
		@DisplayName("같은 버전·이력 없음: 현재 동일 버전 조건 사용, AVAILABLE, 비교 완료·변경 없음")
		void sameVersionNoRevision() {
			var d = mapper.detail(row());
			assertThat(d.appliedConditions()).isEqualTo(d.latestConditions());
			assertThat(d.appliedConditions().availability()).isEqualTo("AVAILABLE");
			assertThat(d.appliedConditions().termsVersion()).isEqualTo(3);
			assertThat(d.changedFields()).isEmpty();
			assertThat(d.unavailableComparisonFields()).isEmpty();
			assertThat(d.comparisonComplete()).isTrue();
		}

		@Test
		@DisplayName("다른 버전·이력 없음: UNAVAILABLE, 모든 값 null, 최신 값으로 채우지 않음, 변경 항목 없음·비교 불완전")
		void otherVersionNoRevision() {
			var d = mapper.detail(row(v -> { v.put("appliedTermsVersion", 2); v.put("latestTermsVersion", 3); }));
			var applied = d.appliedConditions();
			assertThat(applied.availability()).isEqualTo("UNAVAILABLE");
			assertThat(applied.termsVersion()).isEqualTo(2);
			assertThat(applied.workDate()).isNull();
			assertThat(applied.workLocation()).isNull();
			assertThat(applied.latitude()).isNull();
			assertThat(applied.description()).isNull();
			assertThat(applied.plannedStartAt()).isNull();
			assertThat(applied.cropName()).isNull();
			assertThat(applied.unavailableFields()).containsExactlyElementsOf(ALL_FIELDS);
			assertThat(d.latestConditions().availability()).isEqualTo("AVAILABLE");
			assertThat(d.changedFields()).isEmpty();
			assertThat(d.unavailableComparisonFields()).containsExactlyElementsOf(ALL_FIELDS);
			assertThat(d.comparisonComplete()).isFalse();
			assertThat(d.conditionComparisonStatus()).isEqualTo("CHANGED");
		}

		@Test
		@DisplayName("지원 버전 미확인(null): UNAVAILABLE·termsVersion null·UNKNOWN, 이력 행이 있어도 사용하지 않음")
		void unknownVersion() {
			var d = mapper.detail(row(v -> { revision(v); v.put("appliedTermsVersion", null); }));
			assertThat(d.appliedConditions().availability()).isEqualTo("UNAVAILABLE");
			assertThat(d.appliedConditions().termsVersion()).isNull();
			assertThat(d.conditionComparisonStatus()).isEqualTo("UNKNOWN");
			assertThat(d.comparisonComplete()).isFalse();
		}

		@Test
		@DisplayName("대응 이력(V6) 사용: 이력 값 반환, 좌표·작업 내용은 null·확인 불가, 최신 값으로 보완하지 않음")
		void revisionValues() {
			var d = mapper.detail(row(v -> {
				v.put("appliedTermsVersion", 1); v.put("latestTermsVersion", 2); revision(v);
				v.put("revisionStartAt", LocalDateTime.parse("2026-12-10T08:00")); v.put("revisionEndAt", LocalDateTime.parse("2026-12-10T16:00"));
			}));
			var applied = d.appliedConditions();
			assertThat(applied.availability()).isEqualTo("PARTIAL");
			assertThat(applied.termsVersion()).isEqualTo(1);
			assertThat(applied.plannedStartAt()).isEqualTo(at("2026-12-10T08:00+09:00"));
			assertThat(applied.plannedEndAt()).isEqualTo(at("2026-12-10T16:00+09:00"));
			assertThat(applied.latitude()).isNull();
			assertThat(applied.longitude()).isNull();
			assertThat(applied.description()).isNull();
			assertThat(applied.unavailableFields()).containsExactly("latitude", "longitude", "description");
			assertThat(d.latestConditions().latitude()).isEqualByComparingTo("37");
			assertThat(d.latestConditions().description()).isEqualTo("가상 작업 내용");
			assertThat(d.changedFields()).containsExactly("plannedStartAt", "plannedEndAt");
			assertThat(d.unavailableComparisonFields()).containsExactly("latitude", "longitude", "description");
			assertThat(d.comparisonComplete()).isFalse();
		}

		@Test
		@DisplayName("같은 버전이라도 대응 이력이 있으면 이력 저장값을 우선(좌표·작업 내용은 확인 불가)")
		void sameVersionWithRevision() {
			var d = mapper.detail(row(v -> { revision(v); v.put("revisionWorkLocation", "이력 작업지"); }));
			assertThat(d.appliedConditions().workLocation()).isEqualTo("이력 작업지");
			assertThat(d.changedFields()).containsExactly("workLocation");
			assertThat(d.comparisonComplete()).isFalse();
			assertThat(d.conditionComparisonStatus()).isEqualTo("UNCHANGED");
		}

		@Test
		@DisplayName("변경 항목 탐지: 근무일·작업지·작목·작업 유형·정원·일급·시각이 각각 다르면 정확히 그 항목만")
		void changedFields() {
			var d = mapper.detail(row(v -> {
				v.put("appliedTermsVersion", 1); v.put("latestTermsVersion", 5); revision(v);
				v.put("revisionWorkDate", LocalDate.parse("2026-12-09")); v.put("revisionWorkLocation", "예전 작업지");
				v.put("revisionCropCode", "FT07065800065800"); v.put("revisionWorkTypeCode", "0004");
				v.put("revisionHeadcount", 5); v.put("revisionDailyWage", 130000L);
				v.put("revisionBreakStartAt", LocalDateTime.parse("2026-12-10T11:30")); v.put("revisionBreakEndAt", LocalDateTime.parse("2026-12-10T12:30"));
			}));
			assertThat(d.changedFields()).containsExactly("workDate", "workLocation", "cropCode", "workTypeCode", "headcount",
				"dailyWage", "breakStartAt", "breakEndAt");
			assertThat(d.appliedConditions().cropName()).isEqualTo("용과");
			assertThat(d.appliedConditions().workTypeName()).isEqualTo("밑거름 주기");
		}

		@Test
		@DisplayName("시간대 문자열만 다르고 오프셋 시각이 같으면 timeZone만 변경, 시각은 변경 아님")
		void zoneOnly() {
			var d = mapper.detail(row(v -> { v.put("appliedTermsVersion", 1); v.put("latestTermsVersion", 2); revision(v); v.put("revisionTimeZone", "GMT+9"); }));
			assertThat(d.appliedConditions().plannedStartAt()).isEqualTo(d.latestConditions().plannedStartAt());
			assertThat(d.changedFields()).containsExactly("timeZone");
		}

		@Test
		@DisplayName("비교 불가 항목은 변경으로 단정하지 않음(한쪽만 확인 불가여도 changedFields에서 제외)")
		void unavailableNotChanged() {
			var d = mapper.detail(row(v -> { v.put("appliedTermsVersion", 1); v.put("latestTermsVersion", 2); revision(v);
				v.put("latitude", null); v.put("longitude", null); v.put("revisionTimeZone", null); }));
			assertThat(d.changedFields()).isEmpty();
			assertThat(d.unavailableComparisonFields()).containsExactlyInAnyOrder("latitude", "longitude", "description",
				"timeZone", "plannedStartAt", "plannedEndAt", "breakStartAt", "breakEndAt");
			assertThat(d.latestConditions().unavailableFields()).containsExactly("latitude", "longitude");
			assertThat(d.latestConditions().availability()).isEqualTo("PARTIAL");
		}

		@Test
		@DisplayName("unavailableComparisonFields = 지원 당시 + 최신의 확인 불가 합집합, comparisonComplete는 그 집합이 비었을 때만 true")
		void consistency() {
			var d = mapper.detail(row(v -> { v.put("description", null); }));
			assertThat(d.appliedConditions().unavailableFields()).containsExactly("description");
			assertThat(d.unavailableComparisonFields()).containsExactly("description");
			assertThat(d.comparisonComplete()).isFalse();
			assertThat(d.appliedConditions().availability()).isEqualTo("PARTIAL");
		}

		@Test
		@DisplayName("BigDecimal 비교 보조 함수: 소수 자릿수 표현만 다르면 같은 값(현재 경로에서는 좌표가 이력에 없어 직접 확인)")
		void bigDecimalScale() {
			Boolean same = ReflectionTestUtils.invokeMethod(ApplicationConditionMapper.class, "equalValue",
				new BigDecimal("37.0"), new BigDecimal("37.000000"));
			Boolean different = ReflectionTestUtils.invokeMethod(ApplicationConditionMapper.class, "equalValue",
				new BigDecimal("37.000001"), new BigDecimal("37.000000"));
			assertThat(same).isTrue();
			assertThat(different).isFalse();
		}
	}

	@Nested
	@DisplayName("휴게·시간대")
	class BreakAndZone {
		@Test
		@DisplayName("양쪽 휴게 null·누락 표시 없음 = 휴게 없음(AVAILABLE, 확인 불가 아님)")
		void noBreak() {
			var d = mapper.detail(row(v -> { v.put("breakStartAt", null); v.put("breakEndAt", null); }));
			assertThat(d.latestConditions().breakStartAt()).isNull();
			assertThat(d.latestConditions().unavailableFields()).isEmpty();
			assertThat(d.latestConditions().availability()).isEqualTo("AVAILABLE");
			assertThat(d.comparisonComplete()).isTrue();
		}

		@Test
		@DisplayName("휴게 시각 한쪽만 있으면 누락 쪽을 확인 불가로 표시(휴게 없음으로 바꾸지 않음)")
		void halfBreak() {
			var d = mapper.detail(row(v -> v.put("breakEndAt", null)));
			assertThat(d.latestConditions().breakStartAt()).isEqualTo(at("2026-12-10T12:00+09:00"));
			assertThat(d.latestConditions().unavailableFields()).containsExactly("breakEndAt");
		}

		@Test
		@DisplayName("지원 당시 휴게 없음 → 최신 휴게 있음은 비교 가능한 변경")
		void breakAdded() {
			var d = mapper.detail(row(v -> { v.put("appliedTermsVersion", 1); v.put("latestTermsVersion", 2); revision(v);
				v.put("revisionBreakStartAt", null); v.put("revisionBreakEndAt", null); }));
			assertThat(d.changedFields()).containsExactly("breakStartAt", "breakEndAt");
			assertThat(d.appliedConditions().unavailableFields()).doesNotContain("breakStartAt", "breakEndAt");
		}

		@ParameterizedTest
		@ValueSource(strings = {"", " ", "Mars/Base", "GMT+99"})
		@DisplayName("시간대 누락·해석 불가: 시각을 추측하지 않고 timeZone·모든 시각 필드를 확인 불가로 표시")
		void badZone(String zone) {
			var latest = mapper.detail(row(v -> v.put("timeZone", zone))).latestConditions();
			assertThat(latest.plannedStartAt()).isNull();
			assertThat(latest.breakStartAt()).isNull();
			assertThat(latest.unavailableFields()).containsExactly("timeZone", "plannedStartAt", "plannedEndAt", "breakStartAt", "breakEndAt");
			assertThat(latest.timeZone()).isEqualTo(zone);
		}

		@Test
		@DisplayName("시간대 null도 같은 처리")
		void nullZone() {
			assertThat(mapper.detail(row(v -> v.put("timeZone", null))).latestConditions().unavailableFields())
				.contains("timeZone", "plannedStartAt");
		}

		@Test
		@DisplayName("DST 겹침(모호)·공백(존재하지 않는) 현지 시각은 오프셋을 고르지 않고 확인 불가")
		void dst() {
			var d = mapper.detail(row(v -> {
				v.put("timeZone", "America/New_York");
				v.put("plannedStartAt", LocalDateTime.parse("2026-11-01T01:30")); // 겹침
				v.put("plannedEndAt", LocalDateTime.parse("2026-11-01T09:00"));
				v.put("breakStartAt", LocalDateTime.parse("2026-03-08T02:30")); // 공백
				v.put("breakEndAt", LocalDateTime.parse("2026-11-01T05:00"));
			}));
			var latest = d.latestConditions();
			assertThat(latest.plannedStartAt()).isNull();
			assertThat(latest.breakStartAt()).isNull();
			assertThat(latest.plannedEndAt()).isEqualTo(at("2026-11-01T09:00-05:00"));
			assertThat(latest.breakEndAt()).isEqualTo(at("2026-11-01T05:00-05:00"));
			assertThat(latest.unavailableFields()).containsExactly("plannedStartAt", "breakStartAt");
		}

		@Test
		@DisplayName("지역 시간대의 정상 시각은 해당 오프셋(서울 +09:00)으로 표시")
		void normal() {
			assertThat(mapper.detail(row()).latestConditions().plannedStartAt()).isEqualTo(at("2026-12-10T09:00+09:00"));
		}
	}

	@Nested
	@DisplayName("코드 이름")
	class Codes {
		@Test
		@DisplayName("알려진 코드는 코드·이름 반환")
		void known() {
			var latest = mapper.detail(row()).latestConditions();
			assertThat(latest.cropCode()).isEqualTo(CROP);
			assertThat(latest.cropName()).isEqualTo("콩(일반)");
			assertThat(latest.workTypeCode()).isEqualTo(WORK_TYPE);
			assertThat(latest.workTypeName()).isEqualTo("경운");
		}

		@Test
		@DisplayName("모르는 코드는 원본 코드 유지·이름 null, 확인 불가로 표시하지 않음")
		void unknown() {
			var latest = mapper.detail(row(v -> { v.put("cropCode", "ZZ_UNKNOWN"); v.put("workTypeCode", "9999"); })).latestConditions();
			assertThat(latest.cropCode()).isEqualTo("ZZ_UNKNOWN");
			assertThat(latest.cropName()).isNull();
			assertThat(latest.workTypeCode()).isEqualTo("9999");
			assertThat(latest.workTypeName()).isNull();
			assertThat(latest.unavailableFields()).isEmpty();
		}
	}

	@Nested
	@DisplayName("확정 약정")
	class Agreed {
		@Test
		@DisplayName("확정 근무가 없으면 assignment=null")
		void none() {
			assertThat(mapper.detail(row()).assignment()).isNull();
		}

		@Test
		@DisplayName("약정은 확정 근무 저장값만 사용(최신 공고와 다른 작업지·좌표·일급·시각 유지), 확정 시각 +09:00")
		void stored() {
			var a = mapper.detail(row(v -> assignment(v))).assignment();
			assertThat(a.assignmentId()).isEqualTo(700L);
			assertThat(a.confirmedAt()).isEqualTo(at("2026-12-02T10:00:00.000001+09:00"));
			assertThat(a.completedAt()).isNull();
			assertThat(a.change()).isNull();
			var agreed = a.agreedConditions();
			assertThat(agreed.availability()).isEqualTo("AVAILABLE");
			assertThat(agreed.workLocation()).isEqualTo("약정 당시 작업지");
			assertThat(agreed.latitude()).isEqualByComparingTo("36.5");
			assertThat(agreed.dailyWage()).isEqualTo(110000L);
			assertThat(agreed.plannedStartAt()).isEqualTo(at("2026-12-10T08:00+09:00"));
			assertThat(agreed.cropName()).isEqualTo("콩(일반)");
			assertThat(agreed.workTypeName()).isEqualTo("경운");
			assertThat(agreed.unavailableFields()).isEmpty();
		}

		@Test
		@DisplayName("기존 약정 누락(근무일·시간대·좌표·확정 시각)은 PARTIAL·unavailableFields, 최신 공고 값으로 보완하지 않음")
		void missing() {
			var a = mapper.detail(row(v -> { assignment(v); v.put("agreedWorkDate", null); v.put("agreedTimeZone", null);
				v.put("agreedLatitude", null); v.put("agreedLongitude", null); v.put("confirmedAtUtc", null); })).assignment();
			var agreed = a.agreedConditions();
			assertThat(agreed.availability()).isEqualTo("PARTIAL");
			assertThat(agreed.workDate()).isNull();
			assertThat(agreed.latitude()).isNull();
			assertThat(agreed.plannedStartAt()).isNull();
			assertThat(a.confirmedAt()).isNull();
			assertThat(agreed.unavailableFields()).containsExactly("workDate", "latitude", "longitude", "confirmedAt", "timeZone",
				"plannedStartAt", "plannedEndAt", "breakStartAt", "breakEndAt");
		}

		@Test
		@DisplayName("약정 휴게 양쪽 null은 휴게 없음(누락 아님)")
		void agreedNoBreak() {
			var agreed = mapper.detail(row(v -> { assignment(v); v.put("agreedBreakStartAt", null); v.put("agreedBreakEndAt", null); }))
				.assignment().agreedConditions();
			assertThat(agreed.availability()).isEqualTo("AVAILABLE");
			assertThat(agreed.breakStartAt()).isNull();
		}

		@Test
		@DisplayName("확정 후 취소: 변경 유형·사유·설명·적용/기록/수정 시각(+09:00), 취소 후에도 약정 반환")
		void cancelled() {
			var d = mapper.detail(row(v -> { assignment(v); v.put("displayStatus", "CANCELLED"); v.put("changeType", "CANCELLED");
				v.put("reasonCode", "FARM_CIRCUMSTANCES"); v.put("reasonDetail", "가상 사유");
				v.put("effectiveAtUtc", LocalDateTime.parse("2026-12-05T01:00:00.000001"));
				v.put("recordedAtUtc", LocalDateTime.parse("2026-12-05T01:00:00.000001"));
				v.put("updatedAtUtc", LocalDateTime.parse("2026-12-05T02:00")); }));
			assertThat(d.cancellationType()).isEqualTo("ASSIGNMENT_CANCELLED");
			var change = d.assignment().change();
			assertThat(change.changeType()).isEqualTo("CANCELLED");
			assertThat(change.reasonCode()).isEqualTo("FARM_CIRCUMSTANCES");
			assertThat(change.weatherReasonCode()).isNull();
			assertThat(change.reasonDetail()).isEqualTo("가상 사유");
			assertThat(change.effectiveAt()).isEqualTo(at("2026-12-05T10:00:00.000001+09:00"));
			assertThat(change.updatedAt()).isEqualTo(at("2026-12-05T11:00+09:00"));
			assertThat(d.assignment().agreedConditions().workLocation()).isEqualTo("약정 당시 작업지");
		}

		@Test
		@DisplayName("조기 종료: 기상 사유 코드 포함, 약정과 completedAt=null 유지")
		void earlyEnded() {
			var d = mapper.detail(row(v -> { assignment(v); v.put("displayStatus", "EARLY_ENDED"); v.put("changeType", "EARLY_ENDED");
				v.put("reasonCode", "WEATHER"); v.put("weatherReasonCode", "RAIN");
				v.put("effectiveAtUtc", LocalDateTime.parse("2026-12-10T05:00")); }));
			assertThat(d.cancellationType()).isNull();
			assertThat(d.assignment().change().weatherReasonCode()).isEqualTo("RAIN");
			assertThat(d.assignment().change().effectiveAt()).isEqualTo(at("2026-12-10T14:00+09:00"));
			assertThat(d.assignment().completedAt()).isNull();
			assertThat(d.assignment().agreedConditions()).isNotNull();
		}

		@Test
		@DisplayName("완료: 저장된 completed_at_utc만 반환")
		void completed() {
			var a = mapper.detail(row(v -> { assignment(v); v.put("displayStatus", "COMPLETED");
				v.put("completedAtUtc", LocalDateTime.parse("2026-12-10T08:00")); })).assignment();
			assertThat(a.completedAt()).isEqualTo(at("2026-12-10T17:00+09:00"));
		}
	}

	@Test
	@DisplayName("상세 머리 정보는 목록 항목 변환과 같은 값(ID·회차·현재·상태·시각·버전)")
	void detailHeader() {
		var source = row(v -> { v.put("currentFlag", 0); v.put("applicationRound", 2); v.put("latestTermsVersion", 4); });
		var item = mapper.item(source);
		MyApplicationResponses.Detail d = mapper.detail(source);
		assertThat(d.applicationId()).isEqualTo(item.applicationId());
		assertThat(d.applicationRound()).isEqualTo(2);
		assertThat(d.current()).isFalse();
		assertThat(d.appliedAt()).isEqualTo(item.appliedAt());
		assertThat(d.appliedTimeBasis()).isEqualTo("UTC");
		assertThat(d.latestTermsVersion()).isEqualTo(4);
		assertThat(d.conditionComparisonStatus()).isEqualTo("CHANGED");
	}
}
