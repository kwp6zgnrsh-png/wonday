package com.wonday.insurance.platform;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
class WorkChangePollingService {

	private final PlatformLinkRepository links;
	private final PlatformWorkCursorRepository cursors;
	private final PlatformWorkEventRepository events;
	private final FarmWorkClient farm;
	private final ObjectMapper json;
	private final Clock clock;
	private final WorkPollingProperties properties;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean pollPage(long linkId) {
		// Serialize page ingestion across scheduler instances and consent renewal.
		PlatformLink link = links.lockPollingLink(linkId).orElse(null);
		if (link == null) return false;
		long workerId = link.getWorkerId();
		LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
		PlatformWorkCursor cursor = cursors.findById(workerId)
			.orElseGet(() -> new PlatformWorkCursor(workerId, now));
		FarmWorkResponse.Page page = farm.changes(workerId, cursor.getCursor(), properties.pageSize());
		for (FarmWorkResponse.Change change : page.items()) {
			PlatformWorkEvent existing = events.findBySourceChangeIdAndSourceVersion(
				change.changeId(), change.sourceVersion()).orElse(null);
			if (existing != null) {
				verifyDuplicate(existing, change, workerId);
				continue;
			}
			FarmWorkResponse.Assignment assignment = farm.assignment(workerId, change.assignmentId());
			events.saveAndFlush(new PlatformWorkEvent(linkId, change, json.writeValueAsString(change),
				json.writeValueAsString(assignment), now));
		}
		cursor.advance(page.nextCursor(), now);
		cursors.saveAndFlush(cursor);
		return page.hasMore();
	}

	private void verifyDuplicate(PlatformWorkEvent existing, FarmWorkResponse.Change change, long workerId) {
		PlatformLink owner = links.findById(existing.getPlatformLinkId()).orElseThrow();
		if (!Long.valueOf(workerId).equals(owner.getWorkerId()) || existing.getAssignmentId() != change.assignmentId()
			|| (existing.getSourcePayload() != null
				&& !json.readTree(existing.getSourcePayload()).equals(json.readTree(json.writeValueAsString(change))))) {
			throw FarmWorkClient.invalidResponse();
		}
	}
}
