package com.wonday.farm.job.dto;

import com.wonday.farm.job.serialization.StrictIntegerDeserializer;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import tools.jackson.databind.annotation.JsonDeserialize;

// 조회한 뒤 일정이 변경됐다면 다시 확인하도록, 화면이 읽었던 조건 버전을 받는다.
// 소유자·취소 시각·상태는 클라이언트가 지정하지 않는다.
public record CancelWorkScheduleRequest(
	@NotNull @Positive @JsonDeserialize(using = StrictIntegerDeserializer.class) Integer expectedTermsVersion
) {}
