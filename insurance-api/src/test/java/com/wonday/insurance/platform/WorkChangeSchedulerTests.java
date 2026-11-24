package com.wonday.insurance.platform;

import static org.mockito.Mockito.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkChangeSchedulerTests {
	@Test
	void failedWorkerDoesNotBlockOthersAndNextCycleRetries() {
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
}
