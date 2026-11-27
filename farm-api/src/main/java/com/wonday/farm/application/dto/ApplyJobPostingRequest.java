package com.wonday.farm.application.dto;

import java.util.List;
import com.wonday.farm.job.serialization.StrictIntegerDeserializer;
import com.wonday.farm.job.serialization.StrictLongDeserializer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

// 근로자 ID는 세션에서 가져온다. 날짜마다 화면에서 확인한 조건 버전을 받는다.
public record ApplyJobPostingRequest(
	@NotNull @Size(min = 1, max = 100) List<@NotNull @Valid Schedule> schedules
) {
	public record Schedule(
		@NotNull @Positive @JsonDeserialize(using = StrictLongDeserializer.class) Long workScheduleId,
		@NotNull @Positive @JsonDeserialize(using = StrictIntegerDeserializer.class) Integer termsVersion
	) {}
}