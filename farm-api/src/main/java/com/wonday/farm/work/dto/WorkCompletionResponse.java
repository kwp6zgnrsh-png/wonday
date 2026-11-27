package com.wonday.farm.work.dto;

import java.time.OffsetDateTime;

// 완료 처리자 ID와 현재 공고의 조건은 응답하지 않는다.
public record WorkCompletionResponse(long assignmentId, OffsetDateTime completedAt) {}