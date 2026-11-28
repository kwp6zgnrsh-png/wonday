package com.wonday.farm.work.dto;

import java.time.OffsetDateTime;
import com.fasterxml.jackson.annotation.JsonFormat;

public record WorkSelectionResponse(long applicationId, long workScheduleId, long workerId,
        String status, long assignmentId, Integer agreedTermsVersion,
        @JsonFormat(shape = JsonFormat.Shape.STRING, without = JsonFormat.Feature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
        OffsetDateTime confirmedAt,
        String workStatus,
        @JsonFormat(shape = JsonFormat.Shape.STRING, without = JsonFormat.Feature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
        OffsetDateTime completedAt) {}
