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
					// process의 롤백 후 재시도를 예약하고 다음 이벤트 처리를 계속한다.
					events.defer(eventId);
				} catch (RuntimeException storageFailure) {
					// 예약 저장까지 실패해도 미처리 상태는 유지되므로 이후 실행에서 다시 선택된다.
					log.warn("Could not persist claim intake retry for event {} ({}).",
						eventId, storageFailure.getClass().getSimpleName());
				}
				log.warn("Claim intake failed for event {} ({}). Retrying from pending state.",
					eventId, exception.getClass().getSimpleName());
			}
		}
	}
}
