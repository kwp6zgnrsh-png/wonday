package com.wonday.farm.application.testsupport;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import com.wonday.farm.application.repository.MyApplicationQueryRepository.Row;

// APP-04 단위 테스트용 가상 조회 행. 실제 SQL·projection 결과가 아니라 getter 이름에 맞춘 값 묶음이다.
// 기본값: 현재 1회차 PENDING, 지원 버전 3 = 최신 버전 3, 조건 이력 없음, 확정 근무 없음, Asia/Seoul.
public final class MyApplicationRows {
	public static final String CROP = "FC03030100030103"; // 콩(일반)
	public static final String WORK_TYPE = "0003"; // 경운

	private MyApplicationRows() {}

	@SuppressWarnings("unchecked")
	public static <T> T stub(Class<T> type, Map<String, Object> values) {
		var copy = new LinkedHashMap<>(values);
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
			String name = method.getName();
			if (name.equals("toString")) return type.getSimpleName() + copy;
			if (name.equals("hashCode")) return System.identityHashCode(proxy);
			if (name.equals("equals")) return proxy == args[0];
			String key = Character.toLowerCase(name.charAt(3)) + name.substring(4);
			Object value = copy.get(key);
			if (value == null && method.getReturnType().isPrimitive()) {
				throw new IllegalStateException("기본형 getter 값 누락: " + key);
			}
			return value;
		});
	}

	public static Map<String, Object> base() {
		var v = new LinkedHashMap<String, Object>();
		v.put("applicationId", 501L); v.put("workScheduleId", 201L); v.put("applicationRound", 1); v.put("currentFlag", 1);
		v.put("applicationStatus", "PENDING");
		v.put("legacyAppliedAt", LocalDateTime.parse("2026-12-01T06:00:00.123456"));
		v.put("appliedAtUtc", LocalDateTime.parse("2026-12-01T06:00:00.123456"));
		v.put("sortAppliedAt", LocalDateTime.parse("2026-12-01T06:00:00.123456"));
		v.put("appliedTermsVersion", 3);
		v.put("jobPostingId", 101L); v.put("title", "[가상] 콩 경운 작업"); v.put("description", "가상 작업 내용");
		v.put("timeZone", "Asia/Seoul"); v.put("workLocation", "가상 작업지");
		v.put("latitude", new BigDecimal("37.000000")); v.put("longitude", new BigDecimal("127.000000"));
		v.put("cropCode", CROP); v.put("workTypeCode", WORK_TYPE); v.put("headcount", 3); v.put("dailyWage", 120000L);
		v.put("workDate", LocalDate.parse("2026-12-10"));
		v.put("plannedStartAt", LocalDateTime.parse("2026-12-10T09:00:00"));
		v.put("plannedEndAt", LocalDateTime.parse("2026-12-10T17:00:00"));
		v.put("breakStartAt", LocalDateTime.parse("2026-12-10T12:00:00"));
		v.put("breakEndAt", LocalDateTime.parse("2026-12-10T13:00:00"));
		v.put("latestTermsVersion", 3); v.put("scheduleStatus", "OPEN");
		v.put("displayStatus", "PENDING");
		return v;
	}

	public static Row row(Consumer<Map<String, Object>> change) {
		var values = base();
		change.accept(values);
		return stub(Row.class, values);
	}

	public static Row row() { return row(v -> {}); }

	// 지원 버전과 같은 terms_version의 V6 이력 행(좌표·작업 내용 컬럼 없음). 기본 값은 최신 공고와 같다.
	public static void revision(Map<String, Object> v) {
		v.put("revisionId", 900L); v.put("revisionWorkDate", v.get("workDate")); v.put("revisionTimeZone", v.get("timeZone"));
		v.put("revisionWorkLocation", v.get("workLocation")); v.put("revisionCropCode", v.get("cropCode"));
		v.put("revisionWorkTypeCode", v.get("workTypeCode")); v.put("revisionHeadcount", v.get("headcount"));
		v.put("revisionDailyWage", v.get("dailyWage")); v.put("revisionStartAt", v.get("plannedStartAt"));
		v.put("revisionEndAt", v.get("plannedEndAt")); v.put("revisionBreakStartAt", v.get("breakStartAt"));
		v.put("revisionBreakEndAt", v.get("breakEndAt"));
	}

	// 확정 근무에 저장된 약정. 최신 공고 값과 일부러 다르게 둬서 보완 여부를 구분한다.
	public static void assignment(Map<String, Object> v) {
		v.put("applicationStatus", "SELECTED"); v.put("displayStatus", "CONFIRMED");
		v.put("assignmentId", 700L); v.put("confirmedAtUtc", LocalDateTime.parse("2026-12-02T01:00:00.000001"));
		v.put("agreedWorkDate", LocalDate.parse("2026-12-10")); v.put("agreedTimeZone", "Asia/Seoul");
		v.put("agreedWorkLocation", "약정 당시 작업지"); v.put("agreedLatitude", new BigDecimal("36.500000"));
		v.put("agreedLongitude", new BigDecimal("126.500000")); v.put("agreedCropCode", CROP);
		v.put("agreedWorkTypeCode", WORK_TYPE); v.put("agreedDailyWage", 110000L);
		v.put("agreedStartAt", LocalDateTime.parse("2026-12-10T08:00:00"));
		v.put("agreedEndAt", LocalDateTime.parse("2026-12-10T16:00:00"));
		v.put("agreedBreakStartAt", LocalDateTime.parse("2026-12-10T12:00:00"));
		v.put("agreedBreakEndAt", LocalDateTime.parse("2026-12-10T13:00:00"));
	}
}
