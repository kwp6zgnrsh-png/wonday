package com.wonday.insurance.platform;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.support.SimpleTriggerContext;

class WorkChangeSchedulerTests {
	@ParameterizedTest
	@CsvSource({
		"2026-11-24T15:00:00Z, 2026-11-25T09:00:00Z",
		"2026-11-25T08:59:59Z, 2026-11-25T09:00:00Z",
		"2026-11-25T09:00:00Z, 2026-11-26T09:00:00Z",
		"2026-11-25T09:00:01Z, 2026-11-26T09:00:00Z"
	})
	void registeredScheduleRunsAtDaily1800Gmt9EvenWithUtcServerClock(String now, String next) {
		// 실제 Spring 스케줄 등록 결과를 검사한다. 이전 간격 설정을 남겨도 일일 일정만 등록돼야 한다.
		scheduledContext().withPropertyValues("insurance.farm.polling.enabled=true",
			"insurance.farm.polling.delay-ms=1").run(context -> {
			assertThat(context).hasNotFailed();
			var tasks = context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks();
			assertThat(tasks).singleElement().satisfies(task -> {
				assertThat(task.getTask()).isInstanceOf(CronTask.class);
				var trigger = ((CronTask) task.getTask()).getTrigger();
				var triggerContext = new SimpleTriggerContext(Clock.fixed(Instant.parse(now), ZoneOffset.UTC));
				assertThat(trigger.nextExecution(triggerContext)).isEqualTo(Instant.parse(next));
			});
		});
	}

	@Test
	void disabledPollingRegistersNoDailyTask() {
		scheduledContext().withPropertyValues("insurance.farm.polling.enabled=false").run(context -> {
			assertThat(context).hasNotFailed().doesNotHaveBean(WorkChangeScheduler.class);
			assertThat(context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks()).isEmpty();
		});
	}

	@Test
	void failedWorkerDoesNotBlockOthersAndNextDailyRunRetries() {
		PlatformLinkRepository links = mock(PlatformLinkRepository.class);
		WorkChangePollingService polling = mock(WorkChangePollingService.class);
		when(links.findPollingLinkIds()).thenReturn(List.of(1L,2L));
		when(polling.pollPage(1)).thenThrow(new IllegalStateException()).thenReturn(false);
		when(polling.pollPage(2)).thenReturn(true,false,false);
		WorkChangeScheduler scheduler = new WorkChangeScheduler(links,polling,new WorkPollingProperties(100,10));
		scheduler.poll();
		scheduler.poll();
		verify(polling,times(2)).pollPage(1);
		verify(polling,times(3)).pollPage(2);
	}

	@Test
	void pageLimitLetsOtherWorkersProgress() {
		PlatformLinkRepository links = mock(PlatformLinkRepository.class);
		WorkChangePollingService polling = mock(WorkChangePollingService.class);
		when(links.findPollingLinkIds()).thenReturn(List.of(1L,2L));
		when(polling.pollPage(1)).thenReturn(true);
		new WorkChangeScheduler(links,polling,new WorkPollingProperties(100,2)).poll();
		verify(polling,times(2)).pollPage(1);
		verify(polling).pollPage(2);
	}

	private ApplicationContextRunner scheduledContext() {
		return new ApplicationContextRunner()
			.withUserConfiguration(WorkPollingConfiguration.class, WorkChangeScheduler.class)
			.withBean(PlatformLinkRepository.class, () -> mock(PlatformLinkRepository.class))
			.withBean(WorkChangePollingService.class, () -> mock(WorkChangePollingService.class))
			.withPropertyValues("insurance.farm.polling.page-size=100", "insurance.farm.polling.max-pages-per-worker=10");
	}
}
