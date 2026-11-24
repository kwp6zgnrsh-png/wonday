package com.wonday.insurance.claim;

import com.wonday.insurance.platform.PlatformClaimEvents;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "insurance.claim-intake.enabled", havingValue = "true", matchIfMissing = true)
class ClaimIntakeScheduler {
	private final PlatformClaimEvents events;
	private final ClaimIntakeService intake;

	@Scheduled(fixedDelayString = "${insurance.claim-intake.delay-ms:60000}",
		initialDelayString = "${insurance.claim-intake.delay-ms:60000}")
	public void run() {
		for (long eventId : events.pendingIds()) {
			try {
				intake.process(eventId);
			} catch (RuntimeException exception) {
				try {
					events.defer(eventId);
				} catch (RuntimeException storageFailure) {
					log.warn("Could not persist claim intake retry for event {} ({}).",
						eventId, storageFailure.getClass().getSimpleName());
				}
				log.warn("Claim intake failed for event {} ({}). Retrying from pending state.",
					eventId, exception.getClass().getSimpleName());
			}
		}
	}
}
