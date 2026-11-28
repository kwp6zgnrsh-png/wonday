package com.wonday.insurance.claim;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.wonday.insurance.api.ApiTime;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

final class AdminClaimView {
	private AdminClaimView() {}
	record Page<T>(List<T> items, boolean hasMore, Long nextBeforeId) {}
	record Summary(long id, long memberId, String memberName, long contractId, String productName,
		int productVersionNo, long assignmentId, String status, String openedVia, OffsetDateTime openedAt, OffsetDateTime updatedAt) {}
	record Contract(long id, long productVersionId, String productCode, String productName, int productVersionNo,
		String status, OffsetDateTime acceptedAt, OffsetDateTime coverageFrom, OffsetDateTime coverageTo, String termsText) {}
	record IntakeSource(long eventId, long changeId, String sourceVersion, String changeType, String reasonCode,
		String weatherReasonCode, String reasonDetail, OffsetDateTime occurredAt, OffsetDateTime sourceUpdatedAt, OffsetDateTime receivedAt) {}
	record Detail(Summary claim, Contract contract, IntakeSource intakeSource, Work work, ClaimWeatherStationView weatherStation) {}
	record History(long id, String action, String fromStatus, String toStatus, String reason, Long actorId,
		Long assessmentId, OffsetDateTime createdAt) {}

	// DB 약정 원문은 보존하고 응답 일시만 GMT+9로 변환한다. 추가 JSON 필드와 식별정보는 전달하지 않는다.
	@JsonIgnoreProperties(ignoreUnknown = true)
	record Work(@NotNull @Positive Long assignmentId, @NotNull @Positive Long workerId,
		@NotBlank String confirmedAt, @NotBlank String agreedWorkDate, @NotBlank String agreedTimeZone,
		@NotBlank @Size(max = 500) String agreedWorkLocation, @NotBlank @Size(max = 40) String agreedCropCode,
		@NotBlank @Size(max = 40) String agreedWorkTypeCode, @NotBlank String agreedStartAt, @NotBlank String agreedEndAt,
		String agreedBreakStartAt, String agreedBreakEndAt, @NotNull @Positive Long agreedDailyWage,
		@DecimalMin("-90") @DecimalMax("90") BigDecimal agreedLatitude,
		@DecimalMin("-180") @DecimalMax("180") BigDecimal agreedLongitude) {
		Work responseTime() {
			return new Work(assignmentId, workerId, ApiTime.fromOffsetString(confirmedAt), agreedWorkDate, agreedTimeZone,
				agreedWorkLocation, agreedCropCode, agreedWorkTypeCode, ApiTime.fromOffsetString(agreedStartAt),
				ApiTime.fromOffsetString(agreedEndAt), ApiTime.fromOffsetString(agreedBreakStartAt),
				ApiTime.fromOffsetString(agreedBreakEndAt), agreedDailyWage, agreedLatitude, agreedLongitude);
		}
	}
}
