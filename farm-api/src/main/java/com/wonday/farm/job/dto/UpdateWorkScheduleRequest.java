package com.wonday.farm.job.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.wonday.farm.job.serialization.StrictIntegerDeserializer;
import com.wonday.farm.job.serialization.StrictLocalDateDeserializer;
import com.wonday.farm.job.serialization.StrictLocalDateTimeDeserializer;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import tools.jackson.databind.annotation.JsonDeserialize;

// 한 일정의 조건을 전체 입력한다. 공통 정원·일급, 소유자와 모집 상태는 이 요청으로 바꾸지 않는다.
public record UpdateWorkScheduleRequest(
	@NotNull @Positive @JsonDeserialize(using = StrictIntegerDeserializer.class) Integer expectedTermsVersion,
	@NotNull @JsonDeserialize(using = StrictLocalDateDeserializer.class) LocalDate workDate,
	@NotNull @JsonDeserialize(using = StrictLocalDateTimeDeserializer.class) LocalDateTime plannedStartAt,
	@NotNull @JsonDeserialize(using = StrictLocalDateTimeDeserializer.class) LocalDateTime plannedEndAt,
	@JsonDeserialize(using = StrictLocalDateTimeDeserializer.class) LocalDateTime breakStartAt,
	@JsonDeserialize(using = StrictLocalDateTimeDeserializer.class) LocalDateTime breakEndAt
) {}
