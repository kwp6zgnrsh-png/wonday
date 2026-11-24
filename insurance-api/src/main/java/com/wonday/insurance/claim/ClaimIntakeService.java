package com.wonday.insurance.claim;

import com.wonday.insurance.contract.ContractClaimCatalog;
import com.wonday.insurance.platform.PlatformClaimEvents;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
class ClaimIntakeService {
	private final PlatformClaimEvents events;
	private final ContractClaimCatalog contracts;
	private final ClaimRepository claims;
	private final ClaimHistoryRepository history;
	private final Clock clock;

	@Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
	public String process(long eventId) {
		PlatformClaimEvents.Event event = events.lockPending(eventId);
		if (event == null) return "ALREADY_PROCESSED";
		if (!event.activeLink()) return finish(eventId, "INACTIVE_LINK");
		if (!"CANCELLED".equals(event.type())) return finish(eventId, "NOT_CANCELLED");
		if (!"WEATHER".equals(event.reasonCode())) return finish(eventId, "NON_WEATHER_REASON");

		PlatformClaimEvents.Work work = events.verifiedWork(event);
		boolean sameDay = event.occurredAt().atOffset(ZoneOffset.UTC).atZoneSameInstant(ZoneId.of(work.timeZone()))
			.toLocalDate().equals(work.workDate());
		if (!sameDay || !event.occurredAt().isBefore(work.startAt())) {
			return finish(eventId, "NOT_SAME_DAY_BEFORE_START");
		}
		List<Long> contractIds = contracts.lockCoveredContractIds(event.memberId(), event.occurredAt());
		if (contractIds.isEmpty()) return finish(eventId, "NO_COVERED_CONTRACT");

		LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
		int created = 0;
		for (long contractId : contractIds) {
			if (claims.findByContractIdAndAssignmentId(contractId, event.assignmentId()).isPresent()) continue;
			ClaimCase claim = claims.saveAndFlush(new ClaimCase(contractId, event.assignmentId(), eventId, work.snapshot(), now));
			history.saveAndFlush(new ClaimHistory(claim.getId(), now));
			created++;
		}
		return finish(eventId, created > 0 ? "RECEIVED" : "ALREADY_RECEIVED");
	}

	private String finish(long eventId, String result) {
		events.complete(eventId, result);
		return result;
	}
}
