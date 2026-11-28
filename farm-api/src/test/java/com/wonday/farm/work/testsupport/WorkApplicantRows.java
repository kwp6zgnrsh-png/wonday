package com.wonday.farm.work.testsupport;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import com.wonday.farm.application.testsupport.MyApplicationRows;
import com.wonday.farm.work.repository.WorkApplicantRepository;

// WORK-01 단위 테스트용 가상 조회 행. 실제 SQL·projection 결과가 아니다.
// 마스킹 번호·연락처 상태는 SQL이 계산하는 값이므로 여기서는 입력값으로 넣는다.
public final class WorkApplicantRows {
	public static final long EMPLOYER = 10L;
	public static final long POSTING = 101L;
	public static final long SCHEDULE = 201L;

	private WorkApplicantRows() {}

	public static WorkApplicantRepository.Row row(Consumer<Map<String, Object>> change) {
		var values = MyApplicationRows.base();
		values.put("workScheduleId", SCHEDULE); values.put("jobPostingId", POSTING);
		values.put("workerId", 123L); values.put("workerName", "시연 근로자 1");
		values.put("maskedPhone", "010-****-1001"); values.put("phoneState", "AVAILABLE");
		change.accept(values);
		return MyApplicationRows.stub(WorkApplicantRepository.Row.class, values);
	}

	public static WorkApplicantRepository.Row row() { return row(v -> {}); }

	public static WorkApplicantRepository.Schedule schedule(String status, int headcount) {
		var v = new LinkedHashMap<String, Object>();
		v.put("workScheduleId", SCHEDULE); v.put("workDate", LocalDate.parse("2026-12-10"));
		v.put("recruitmentStatus", status); v.put("headcount", headcount);
		return MyApplicationRows.stub(WorkApplicantRepository.Schedule.class, v);
	}

	public static WorkApplicantRepository.Contact contact(long applicationId, String status, int currentFlag, String phone) {
		var v = new LinkedHashMap<String, Object>();
		v.put("applicationId", applicationId); v.put("workerId", 123L); v.put("status", status); v.put("currentFlag", currentFlag);
		v.put("workerName", "시연 근로자 1"); v.put("phone", phone);
		return MyApplicationRows.stub(WorkApplicantRepository.Contact.class, v);
	}
}
