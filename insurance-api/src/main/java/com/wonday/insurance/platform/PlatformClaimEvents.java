package com.wonday.insurance.platform;

import jakarta.validation.Validator;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class PlatformClaimEvents {

	private final PlatformWorkEventRepository events;
	private final PlatformLinkRepository links;
	private final FarmWorkClient farm;
	private final ObjectMapper json;
	private final Validator validator;
	private final Clock clock;

	@Transactional(readOnly = true)
	public List<Long> pendingIds() {
		return events.findPendingIntake(now(), PageRequest.of(0, 100));
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public Event lockPending(long eventId) {
		PlatformWorkEvent event = events.lockPendingIntake(eventId, now()).orElse(null);
		if (event == null) return null;
		PlatformLink original = links.findById(event.getPlatformLinkId()).orElseThrow();
		boolean active = original.getWorkerId() != null
			&& links.lockForClaimIntake(original.getMemberId(), original.getWorkerId()).isPresent();
		return new Event(event.getId(), original.getMemberId(), original.getWorkerId(), event.getAssignmentId(),
			event.getEventType(), event.getReasonCode(), event.getOccurredAt(), event.getWorkSnapshot(), active);
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public Work verifiedWork(Event event) {
		FarmWorkResponse.Assignment assignment;
		if (event.workSnapshot() == null) {
			assignment = farm.assignment(event.workerId(), event.assignmentId());
		} else {
			try {
				assignment = json.readValue(event.workSnapshot(), FarmWorkResponse.Assignment.class);
			} catch (RuntimeException exception) {
				throw FarmWorkClient.invalidResponse();
			}
		}
		if (assignment == null || !validator.validate(assignment).isEmpty()) {
			throw FarmWorkClient.invalidResponse();
		}
		FarmWorkClient.validateAssignment(assignment, event.workerId(), event.assignmentId());
		LocalDateTime confirmedAt = assignment.confirmedAt().withOffsetSameInstant(ZoneOffset.UTC)
			.toLocalDateTime().truncatedTo(ChronoUnit.MICROS);
		if (confirmedAt.isAfter(event.occurredAt())) throw FarmWorkClient.invalidResponse();
		String snapshot = event.workSnapshot();
		if (snapshot == null) {
			snapshot = json.writeValueAsString(assignment);
			events.findById(event.id()).orElseThrow().captureSnapshot(snapshot);
		}
		return new Work(snapshot, assignment.agreedWorkDate(), assignment.agreedTimeZone(),
			assignment.agreedStartAt().withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime().truncatedTo(ChronoUnit.MICROS));
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void complete(long eventId, String result) {
		events.findById(eventId).orElseThrow().finishIntake(result, now());
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void defer(long eventId) {
		events.lockForIntakeRetry(eventId).filter(event -> event.getIntakeProcessedAt() == null)
			.ifPresent(event -> event.deferIntake(now().plusMinutes(1)));
	}

	private LocalDateTime now() {
		return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
	}

	public record Event(long id, long memberId, Long workerId, long assignmentId, String type,
		String reasonCode, LocalDateTime occurredAt, String workSnapshot, boolean activeLink) {}

	public record Work(String snapshot, LocalDate workDate, String timeZone, LocalDateTime startAt) {}
}
