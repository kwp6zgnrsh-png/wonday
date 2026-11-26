package com.wonday.insurance.platform;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;

final class FarmWorkResponse {

	private FarmWorkResponse() {}

	enum ChangeType { CANCELLED, EARLY_ENDED }
	enum ReasonCode { WEATHER, FARM_CIRCUMSTANCES, OTHER }
	enum WeatherReasonCode { RAIN, WIND, HEAT, OTHER }

	record Page(
		@NotNull @Positive Long workerId,
		@NotNull @Size(max = 500) List<@NotNull @Valid Change> items,
		@NotBlank @Size(max = 4096) String nextCursor,
		@NotNull Boolean hasMore) {}

	record Change(
		@NotNull @Positive Long changeId,
		@NotBlank @Size(max = 100) String sourceVersion,
		@NotNull @Positive Long assignmentId,
		@NotNull ChangeType changeType,
		@NotNull ReasonCode reasonCode,
		WeatherReasonCode weatherReasonCode,
		String reasonDetail,
		@NotNull OffsetDateTime effectiveAt,
		@NotNull OffsetDateTime recordedAt,
		@NotNull OffsetDateTime updatedAt) {}

	// 이전 API의 좌표 없는 약정도 읽을 수 있도록 좌표에는 NotNull을 적용하지 않는다.
	record Assignment(
		@NotNull @Positive Long assignmentId,
		@NotNull @Positive Long workerId,
		@NotNull OffsetDateTime confirmedAt,
		@NotNull LocalDate agreedWorkDate,
		@NotBlank @Size(max = 64) String agreedTimeZone,
		@NotBlank @Size(max = 500) String agreedWorkLocation,
		@NotBlank @Size(max = 40) String agreedCropCode,
		@NotBlank @Size(max = 40) String agreedWorkTypeCode,
		@NotNull OffsetDateTime agreedStartAt,
		@NotNull OffsetDateTime agreedEndAt,
		OffsetDateTime agreedBreakStartAt,
		OffsetDateTime agreedBreakEndAt,
		@NotNull @Positive Long agreedDailyWage,
		@DecimalMin("-90") @DecimalMax("90")
		@JsonDeserialize(using = CoordinateDeserializer.class) BigDecimal agreedLatitude,
		@DecimalMin("-180") @DecimalMax("180")
		@JsonDeserialize(using = CoordinateDeserializer.class) BigDecimal agreedLongitude) {}
}
