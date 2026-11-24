package com.wonday.farm.insurance.service;

import com.wonday.farm.insurance.dto.WorkAssignmentResponse;
import com.wonday.farm.insurance.exception.InsuranceApiException;
import com.wonday.farm.work.repository.WorkAssignmentRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkAssignmentQueryService {
	private final InsuranceDataAccess access;
	private final WorkAssignmentRepository assignments;

	public WorkAssignmentQueryService(InsuranceDataAccess access, WorkAssignmentRepository assignments) {
		this.access = access;
		this.assignments = assignments;
	}

	@Transactional(readOnly = true)
	public WorkAssignmentResponse find(long workerId, long assignmentId) {
		if (assignmentId <= 0) throw InsuranceApiException.invalidRequest();
		access.requireWorker(workerId);
		var assignment = assignments.findOwned(workerId, assignmentId).orElseThrow(() ->
			new InsuranceApiException(HttpStatus.NOT_FOUND, "WORK_ASSIGNMENT_NOT_FOUND",
				"해당 근로자의 확정 근무를 찾을 수 없습니다."));
		if (assignment.getAgreedWorkDate() == null || assignment.getConfirmedAtUtc() == null
				|| (assignment.getAgreedBreakStartAt() == null) != (assignment.getAgreedBreakEndAt() == null)) {
			// 기존 행의 누락 조건을 현재 공고로 채우면 약정을 바꾸게 되므로 추측해서 제공하지 않는다.
			throw InsuranceApiException.unavailable();
		}
		var zone = InsuranceTime.zone(assignment.getAgreedTimeZone());
		return new WorkAssignmentResponse(assignment.getId(), workerId,
			InsuranceTime.utc(assignment.getConfirmedAtUtc(), zone), assignment.getAgreedWorkDate(),
			assignment.getAgreedTimeZone(), assignment.getAgreedWorkLocation(), assignment.getAgreedCropCode(),
			assignment.getAgreedWorkTypeCode(), InsuranceTime.local(assignment.getAgreedStartAt(), zone),
			InsuranceTime.local(assignment.getAgreedEndAt(), zone),
			assignment.getAgreedBreakStartAt() == null ? null : InsuranceTime.local(assignment.getAgreedBreakStartAt(), zone),
			assignment.getAgreedBreakEndAt() == null ? null : InsuranceTime.local(assignment.getAgreedBreakEndAt(), zone),
			assignment.getAgreedDailyWage());
	}
}
