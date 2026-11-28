package com.wonday.farm.change.dto;

import java.time.OffsetDateTime;
import com.wonday.farm.change.entity.WorkChange;

// effectiveAt은 실제 종료 시각, recordedAt은 최초 서버 저장 시각이다.
public record WorkEarlyEndResponse(
    long assignmentId, long changeId, WorkChange.Type changeType,
    WorkChange.Reason reasonCode, WorkChange.WeatherReason weatherReasonCode, String reasonDetail,
    OffsetDateTime effectiveAt, OffsetDateTime recordedAt, OffsetDateTime updatedAt, String sourceVersion
) {}
