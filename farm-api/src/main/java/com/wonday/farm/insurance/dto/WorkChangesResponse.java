package com.wonday.farm.insurance.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.wonday.farm.change.entity.WorkChange;

public record WorkChangesResponse(long workerId, List<Item> items, String nextCursor, boolean hasMore) {
	public record Item(long changeId, String sourceVersion, long assignmentId,
		WorkChange.Type changeType, WorkChange.Reason reasonCode, WorkChange.WeatherReason weatherReasonCode,
		String reasonDetail, OffsetDateTime effectiveAt, OffsetDateTime recordedAt, OffsetDateTime updatedAt) {}
}
