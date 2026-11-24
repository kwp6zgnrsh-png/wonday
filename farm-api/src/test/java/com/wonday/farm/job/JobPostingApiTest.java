package com.wonday.farm.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.List;

import com.wonday.farm.auth.FarmPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

class JobPostingApiTest {

	@Test
	void createsPostingForSessionMemberAndReturns201() throws Exception {
		JobPostingService service = mock(JobPostingService.class);
		FarmPrincipal principal = mock(FarmPrincipal.class);
		given(principal.memberId()).willReturn(42L);
		var request = JobPostingServiceTest.request("Asia/Seoul", JobPostingServiceTest.defaultSchedule());
		var expected = new CreateJobPostingResponse(7L, 42L, 2, List.of(
			new CreateJobPostingResponse.Schedule(8L, LocalDate.parse("2026-11-30"), WorkSchedule.Status.OPEN)));
		given(service.create(42L, request)).willReturn(expected);

		assertThat(new JobPostingApi(service).create(principal, request)).isSameAs(expected);
		verify(service).create(42L, request);
		Method create = JobPostingApi.class.getMethod("create", FarmPrincipal.class, CreateJobPostingRequest.class);
		assertThat(create.getAnnotation(ResponseStatus.class).value()).isEqualTo(HttpStatus.CREATED);
	}
}
