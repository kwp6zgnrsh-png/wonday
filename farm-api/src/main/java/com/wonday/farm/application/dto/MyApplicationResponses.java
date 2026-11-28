package com.wonday.farm.application.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonFormat;

// 목록은 현재 회차, 이력은 과거 회차만 제공한다. null은 과거 값을 추측하지 않았다는 뜻이다.
public final class MyApplicationResponses {
	private MyApplicationResponses() {}

	public record Page(List<Item> items, String nextCursor, boolean hasMore) {}
	public record History(Long currentApplicationId, List<Item> items, String nextCursor, boolean hasMore) {}

	public record Item(long applicationId, int applicationRound, boolean current, long jobPostingId,
		String title, long workScheduleId, LocalDate workDate,
		@JsonFormat(shape = JsonFormat.Shape.STRING, without = JsonFormat.Feature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE) OffsetDateTime appliedAt,
		LocalDateTime legacyAppliedAt, String appliedTimeBasis, String applicationStatus, String displayStatus,
		String cancellationType, Long assignmentId,
		@JsonFormat(shape = JsonFormat.Shape.STRING, without = JsonFormat.Feature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE) OffsetDateTime completedAt,
		Integer appliedTermsVersion, int latestTermsVersion, String conditionComparisonStatus,
        com.wonday.farm.application.dto.ConditionConfirmation conditionConfirmation) {
        public Item(long applicationId, int applicationRound, boolean current, long jobPostingId, String title, long workScheduleId, LocalDate workDate, OffsetDateTime appliedAt, LocalDateTime legacyAppliedAt, String appliedTimeBasis, String applicationStatus, String displayStatus, String cancellationType, Long assignmentId, OffsetDateTime completedAt, Integer appliedTermsVersion, int latestTermsVersion, String conditionComparisonStatus) {
            this(applicationId, applicationRound, current, jobPostingId, title, workScheduleId, workDate, appliedAt, legacyAppliedAt, appliedTimeBasis, applicationStatus, displayStatus, cancellationType, assignmentId, completedAt, appliedTermsVersion, latestTermsVersion, conditionComparisonStatus, null);
        }
    }

	public record Detail(long applicationId, int applicationRound, boolean current, long jobPostingId,
		String title, long workScheduleId, String applicationStatus, String displayStatus, String cancellationType,
		@JsonFormat(shape = JsonFormat.Shape.STRING, without = JsonFormat.Feature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE) OffsetDateTime appliedAt,
		LocalDateTime legacyAppliedAt, String appliedTimeBasis, Integer appliedTermsVersion,
		int latestTermsVersion, String conditionComparisonStatus, Conditions appliedConditions,
		Conditions latestConditions, List<String> changedFields, List<String> unavailableComparisonFields,
		boolean comparisonComplete, Assignment assignment,
        com.wonday.farm.application.dto.ConditionConfirmation conditionConfirmation) {
        public Detail(long applicationId, int applicationRound, boolean current, long jobPostingId, String title, long workScheduleId, String applicationStatus, String displayStatus, String cancellationType, OffsetDateTime appliedAt, LocalDateTime legacyAppliedAt, String appliedTimeBasis, Integer appliedTermsVersion, int latestTermsVersion, String conditionComparisonStatus, Conditions appliedConditions, Conditions latestConditions, List<String> changedFields, List<String> unavailableComparisonFields, boolean comparisonComplete, Assignment assignment) {
            this(applicationId, applicationRound, current, jobPostingId, title, workScheduleId, applicationStatus, displayStatus, cancellationType, appliedAt, legacyAppliedAt, appliedTimeBasis, appliedTermsVersion, latestTermsVersion, conditionComparisonStatus, appliedConditions, latestConditions, changedFields, unavailableComparisonFields, comparisonComplete, assignment, null);
        }
    }

	// AVAILABLE / PARTIAL / UNAVAILABLE로 '휴게 없음'과 '과거 기록 없음'을 구분한다.
	public record Conditions(String availability, Integer termsVersion, LocalDate workDate, String timeZone,
		String workLocation, BigDecimal latitude, BigDecimal longitude, String cropCode, String cropName,
		String workTypeCode, String workTypeName, String description, Integer headcount, Long dailyWage,
		@JsonFormat(shape = JsonFormat.Shape.STRING, without = JsonFormat.Feature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE) OffsetDateTime plannedStartAt,
		@JsonFormat(shape = JsonFormat.Shape.STRING, without = JsonFormat.Feature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE) OffsetDateTime plannedEndAt,
		@JsonFormat(shape = JsonFormat.Shape.STRING, without = JsonFormat.Feature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE) OffsetDateTime breakStartAt,
		@JsonFormat(shape = JsonFormat.Shape.STRING, without = JsonFormat.Feature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE) OffsetDateTime breakEndAt,
		List<String> unavailableFields) {}

	public record Assignment(long assignmentId,
		@JsonFormat(shape = JsonFormat.Shape.STRING, without = JsonFormat.Feature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE) OffsetDateTime confirmedAt,
		@JsonFormat(shape = JsonFormat.Shape.STRING, without = JsonFormat.Feature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE) OffsetDateTime completedAt,
		AgreedConditions agreedConditions, Change change) {}

	// 약정은 확정 근무에 저장된 값만 사용한다. 현재 모집의 정원·작업 내용을 약정으로 복사하지 않는다.
	public record AgreedConditions(String availability, LocalDate workDate, String timeZone,
		String workLocation, BigDecimal latitude, BigDecimal longitude, String cropCode, String cropName,
		String workTypeCode, String workTypeName, Long dailyWage,
		@JsonFormat(shape = JsonFormat.Shape.STRING, without = JsonFormat.Feature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE) OffsetDateTime plannedStartAt,
		@JsonFormat(shape = JsonFormat.Shape.STRING, without = JsonFormat.Feature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE) OffsetDateTime plannedEndAt,
		@JsonFormat(shape = JsonFormat.Shape.STRING, without = JsonFormat.Feature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE) OffsetDateTime breakStartAt,
		@JsonFormat(shape = JsonFormat.Shape.STRING, without = JsonFormat.Feature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE) OffsetDateTime breakEndAt,
		List<String> unavailableFields) {}

	public record Change(String changeType, String reasonCode, String weatherReasonCode, String reasonDetail,
		@JsonFormat(shape = JsonFormat.Shape.STRING, without = JsonFormat.Feature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE) OffsetDateTime effectiveAt,
		@JsonFormat(shape = JsonFormat.Shape.STRING, without = JsonFormat.Feature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE) OffsetDateTime recordedAt,
		@JsonFormat(shape = JsonFormat.Shape.STRING, without = JsonFormat.Feature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE) OffsetDateTime updatedAt) {}
}
