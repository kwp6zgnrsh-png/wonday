package com.wonday.farm.work.dto;

import com.wonday.farm.job.serialization.StrictIntegerDeserializer;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import tools.jackson.databind.annotation.JsonDeserialize;

// 클라이언트가 실제로 확인한 버전만 받는다. 회원·소유자·처리 시각은 서버에서 결정한다.
public record TermsVersionRequest(
        @NotNull @Positive @JsonDeserialize(using = StrictIntegerDeserializer.class) Integer termsVersion) {}
