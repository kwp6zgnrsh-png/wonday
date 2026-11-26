package com.wonday.farm.job.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.wonday.farm.job.serialization.StrictCoordinateDeserializer;
import com.wonday.farm.job.serialization.StrictIntegerDeserializer;
import com.wonday.farm.job.serialization.StrictLocalDateDeserializer;
import com.wonday.farm.job.serialization.StrictLocalDateTimeDeserializer;
import com.wonday.farm.job.serialization.StrictLongDeserializer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
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
	// 반올림 전 원본 값의 범위를 검사한다. 숫자 문자열의 자동 변환도 허용하지 않는다.
	@NotNull @DecimalMin("-90") @DecimalMax("90")
	@JsonDeserialize(using = StrictCoordinateDeserializer.class) BigDecimal latitude,
	@NotNull @DecimalMin("-180") @DecimalMax("180")
	@JsonDeserialize(using = StrictCoordinateDeserializer.class) BigDecimal longitude,
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
