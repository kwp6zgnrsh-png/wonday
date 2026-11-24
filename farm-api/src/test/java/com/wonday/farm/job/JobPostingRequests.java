package com.wonday.farm.job;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.json.JsonMapper;

// 공고 등록 요청 본문을 만든다. 값 하나만 바꾸거나 지울 수 있도록 Map으로 다룬다.
// 작목·작업 유형 코드는 팀 공통 코드가 미정이므로 실제 코드처럼 보이지 않는 임의 문자열을 쓴다.
final class JobPostingRequests {
	static final String CROP_CODE = "TEST-CROP";
	static final String WORK_TYPE_CODE = "TEST-WORK";
	private static final JsonMapper JSON = JsonMapper.builder().build();

	private JobPostingRequests() {
	}

	// 날짜 두 개: 첫날은 휴게 있음, 둘째 날은 휴게 없음.
	static Map<String, Object> valid() {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("title", "사과 수확 근로자 모집");
		body.put("description", "사과 수확과 운반 작업입니다.");
		body.put("cropCode", CROP_CODE);
		body.put("workTypeCode", WORK_TYPE_CODE);
		body.put("workLocation", "충청북도 충주시 예시농장");
		body.put("timeZone", "Asia/Seoul");
		body.put("headcount", 2);
		body.put("dailyWage", 120000);
		body.put("schedules", new ArrayList<>(List.of(
			schedule("2026-11-30", "2026-11-30T08:00:00", "2026-11-30T16:00:00", "2026-11-30T12:00:00", "2026-11-30T13:00:00"),
			schedule("2026-12-02", "2026-12-02T08:00:00", "2026-12-02T12:00:00", null, null))));
		return body;
	}

	static Map<String, Object> withOneSchedule(Map<String, Object> schedule) {
		Map<String, Object> body = valid();
		body.put("schedules", new ArrayList<>(List.of(schedule)));
		return body;
	}

	static Map<String, Object> schedule(String workDate, String start, String end, String breakStart, String breakEnd) {
		Map<String, Object> schedule = new LinkedHashMap<>();
		schedule.put("workDate", workDate);
		schedule.put("plannedStartAt", start);
		schedule.put("plannedEndAt", end);
		schedule.put("breakStartAt", breakStart);
		schedule.put("breakEndAt", breakEnd);
		return schedule;
	}

	@SuppressWarnings("unchecked")
	static Map<String, Object> firstSchedule(Map<String, Object> body) {
		return ((List<Map<String, Object>>) body.get("schedules")).getFirst();
	}

	static String json(Object body) {
		return JSON.writeValueAsString(body);
	}
}
