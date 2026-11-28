package com.wonday.farm.application.dto;

import java.time.OffsetDateTime;
import com.fasterxml.jackson.annotation.JsonFormat;

// source는 확인 근거, latestTermsConfirmed는 그 근거가 현재 최신 버전까지 충족하는지 나타낸다.
// 최초 지원만으로 확인된 경우 acknowledgementId/acknowledgedAt은 null이다.
public record ConditionConfirmation(
        String source, Integer confirmedTermsVersion, boolean latestTermsConfirmed,
        Long acknowledgementId, Integer acknowledgedTermsVersion,
        @JsonFormat(shape = JsonFormat.Shape.STRING, without = JsonFormat.Feature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
        OffsetDateTime acknowledgedAt) {}
