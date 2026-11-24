package com.wonday.insurance.platform;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "insurance.farm.polling.enabled", havingValue = "true")
class WorkChangeScheduler {

	private final PlatformLinkRepository links;
	private final WorkChangePollingService polling;
	private final WorkPollingProperties properties;

	@Scheduled(fixedDelayString = "${insurance.farm.polling.delay-ms}",
		initialDelayString = "${insurance.farm.polling.delay-ms}")
	public void poll() {
		for (long linkId : links.findPollingLinkIds()) {
			try {
				for (int page = 0; page < properties.maxPagesPerWorker(); page++) {
					if (!polling.pollPage(linkId)) break;
				}
			} catch (RuntimeException exception) {
				String code = exception instanceof PlatformVerificationException platformException
					? platformException.getCode() : exception.getClass().getSimpleName();
				log.warn("Farm work polling failed for link {} ({}). Retrying from saved cursor next cycle.",
					linkId, code);
			}
		}
	}
}

@Configuration
@EnableScheduling
@EnableConfigurationProperties(WorkPollingProperties.class)
class WorkPollingConfiguration {}
