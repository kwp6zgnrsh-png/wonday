package com.wonday.farm.change.dto;

import com.wonday.farm.change.serialization.CancellationStringDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

// 세 필드 모두 JSON 문자열만 받는다. 숫자·불리언을 사유나 설명으로 자동 변환하지 않는다.
public record WorkCancellationRequest(
    @JsonDeserialize(using = CancellationStringDeserializer.class) String reasonCode,
    @JsonDeserialize(using = CancellationStringDeserializer.class) String weatherReasonCode,
    @JsonDeserialize(using = CancellationStringDeserializer.class) String reasonDetail
) {}
