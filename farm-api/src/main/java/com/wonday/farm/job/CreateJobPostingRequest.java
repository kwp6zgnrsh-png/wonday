package com.wonday.farm.job;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

// 소유자 ID와 모집 상태는 서버에서 지정한다. 시간은 timeZone 기준의 현지 일시다.
public record CreateJobPostingRequest(
	@NotBlank @Size(max = 200) String title,
	@NotBlank String description,
	@NotBlank @Size(max = 40) String cropCode,
	@NotBlank @Size(max = 40) String workTypeCode,
	@NotBlank @Size(max = 500) String workLocation,
	@NotBlank @Size(max = 64) String timeZone,
	@NotNull @Positive @JsonDeserialize(using = StrictIntegerDeserializer.class) Integer headcount,
	@NotNull @Positive @JsonDeserialize(using = StrictLongDeserializer.class) Long dailyWage,
	@NotEmpty List<@NotNull @Valid Schedule> schedules
) {
	public record Schedule(
		@NotNull @JsonDeserialize(using = StrictLocalDateDeserializer.class) LocalDate workDate,
		@NotNull @JsonDeserialize(using = StrictLocalDateTimeDeserializer.class) LocalDateTime plannedStartAt,
		@NotNull @JsonDeserialize(using = StrictLocalDateTimeDeserializer.class) LocalDateTime plannedEndAt,
		@JsonDeserialize(using = StrictLocalDateTimeDeserializer.class) LocalDateTime breakStartAt,
		@JsonDeserialize(using = StrictLocalDateTimeDeserializer.class) LocalDateTime breakEndAt
	) {}
}
