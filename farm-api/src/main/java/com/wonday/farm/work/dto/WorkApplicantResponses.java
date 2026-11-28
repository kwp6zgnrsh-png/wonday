package com.wonday.farm.work.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.wonday.farm.application.dto.MyApplicationResponses;

// 목록·상세에는 마스킹 번호만 포함한다. 전체 번호 응답은 Contact로 분리한다.
public final class WorkApplicantResponses {
	private WorkApplicantResponses() {}
	public record Page(long jobPostingId, long workScheduleId, LocalDate workDate, String recruitmentStatus,
		int headcount, long confirmedCount, long remainingSlots, List<Item> items, String nextCursor, boolean hasMore) {}
	public record Item(long applicationId, long workerId, String name, String maskedPhone, String phoneState,
		int applicationRound, String status,
		@JsonFormat(shape = JsonFormat.Shape.STRING, without = JsonFormat.Feature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE) OffsetDateTime appliedAt,
		LocalDateTime legacyAppliedAt, String appliedTimeBasis, Integer appliedTermsVersion, int latestTermsVersion,
		String conditionComparisonStatus, boolean canViewContact, Long assignmentId, String workStatus,
        com.wonday.farm.application.dto.ConditionConfirmation conditionConfirmation) {
        public Item(long applicationId, long workerId, String name, String maskedPhone, String phoneState, int applicationRound, String status, OffsetDateTime appliedAt, LocalDateTime legacyAppliedAt, String appliedTimeBasis, Integer appliedTermsVersion, int latestTermsVersion, String conditionComparisonStatus, boolean canViewContact, Long assignmentId, String workStatus) {
            this(applicationId, workerId, name, maskedPhone, phoneState, applicationRound, status, appliedAt, legacyAppliedAt, appliedTimeBasis, appliedTermsVersion, latestTermsVersion, conditionComparisonStatus, canViewContact, assignmentId, workStatus, null);
        }
    }
	public record Detail(long applicationId, long workerId, String name, String maskedPhone, String phoneState,
		int applicationRound, String status, boolean canViewContact,
		@JsonFormat(shape = JsonFormat.Shape.STRING, without = JsonFormat.Feature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE) OffsetDateTime appliedAt,
		LocalDateTime legacyAppliedAt, String appliedTimeBasis, Integer appliedTermsVersion, int latestTermsVersion,
		String conditionComparisonStatus, MyApplicationResponses.Conditions appliedConditions,
		MyApplicationResponses.Conditions latestConditions, List<String> changedFields,
		List<String> unavailableComparisonFields, boolean comparisonComplete, MyApplicationResponses.Assignment assignment,
        com.wonday.farm.application.dto.ConditionConfirmation conditionConfirmation) {
        public Detail(long applicationId, long workerId, String name, String maskedPhone, String phoneState, int applicationRound, String status, boolean canViewContact, OffsetDateTime appliedAt, LocalDateTime legacyAppliedAt, String appliedTimeBasis, Integer appliedTermsVersion, int latestTermsVersion, String conditionComparisonStatus, MyApplicationResponses.Conditions appliedConditions, MyApplicationResponses.Conditions latestConditions, List<String> changedFields, List<String> unavailableComparisonFields, boolean comparisonComplete, MyApplicationResponses.Assignment assignment) {
            this(applicationId, workerId, name, maskedPhone, phoneState, applicationRound, status, canViewContact, appliedAt, legacyAppliedAt, appliedTimeBasis, appliedTermsVersion, latestTermsVersion, conditionComparisonStatus, appliedConditions, latestConditions, changedFields, unavailableComparisonFields, comparisonComplete, assignment, null);
        }
    }
	public record Contact(long applicationId, long workerId, String name, String phone, String phoneState) {}
}
