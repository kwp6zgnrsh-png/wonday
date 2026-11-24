package com.wonday.insurance.platform;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

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
		@NotNull @Positive Long agreedDailyWage) {}
}
