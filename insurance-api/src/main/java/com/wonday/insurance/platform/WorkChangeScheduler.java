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

	// 실행 PC의 시간대와 무관하게 GMT+9 기준 매일 18시에 한 회차를 시작한다.
	@Scheduled(cron = "0 0 18 * * *", zone = "GMT+9")
	public void poll() {
		// 한 근로자의 페이지 수를 제한해 다른 근로자의 수집이 계속 밀리지 않게 한다.
		for (long linkId : links.findPollingLinkIds()) {
			try {
				for (int page = 0; page < properties.maxPagesPerWorker(); page++) {
					if (!polling.pollPage(linkId)) break;
				}
			} catch (RuntimeException exception) {
				// 실패한 페이지만 롤백한다. 다음 날 18시에 마지막 성공 커서부터 다시 조회한다.
				String code = exception instanceof PlatformVerificationException platformException
					? platformException.getCode() : exception.getClass().getSimpleName();
				log.warn("Farm work polling failed for link {} ({}). Retrying from saved cursor at next daily run.",
					linkId, code);
			}
		}
	}
}

@Configuration
@EnableScheduling
@EnableConfigurationProperties(WorkPollingProperties.class)
class WorkPollingConfiguration {}
