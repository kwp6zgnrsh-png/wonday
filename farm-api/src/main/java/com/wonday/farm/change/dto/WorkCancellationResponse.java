package com.wonday.farm.change.dto;

import java.time.OffsetDateTime;
import com.wonday.farm.change.entity.WorkChange;

// 재요청에도 같은 변경 ID·버전·최초 시각을 반환한다. 약정과 개인정보는 이 응답에 포함하지 않는다.
public record WorkCancellationResponse(
    long assignmentId,
    long changeId,
    WorkChange.Type changeType,
    WorkChange.Reason reasonCode,
    WorkChange.WeatherReason weatherReasonCode,
    String reasonDetail,
    OffsetDateTime effectiveAt,
    OffsetDateTime recordedAt,
    OffsetDateTime updatedAt,
    String sourceVersion
) {}
