package com.wonday.farm.job.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.lang.reflect.Method;
import java.util.List;

import com.wonday.farm.auth.FarmPrincipal;
import com.wonday.farm.job.dto.MyJobPostingsResponse;
import com.wonday.farm.job.service.JobPostingQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;

// 컨트롤러가 세션 회원 ID만 서비스에 넘기고 결과를 그대로 반환하는지 확인한다 (Spring 컨텍스트 없음).
// 실제 경로 매핑·Security 401/403은 통합 검증 대상이다.
class MyJobPostingApiTest {
	private final JobPostingQueryService service = mock(JobPostingQueryService.class);
	private final MyJobPostingApi api = new MyJobPostingApi(service);

	@Test
	void passesSessionMemberIdToServiceAndReturnsItsResult() {
		FarmPrincipal principal = principal(42L);
		var expected = new MyJobPostingsResponse(List.of());
		given(service.findMine(42L)).willReturn(expected);

		assertThat(api.mine(principal)).isSameAs(expected);
		verify(service).findMine(42L);
		verifyNoMoreInteractions(service);
	}

	@Test
	void serviceRejectionIsPropagated() {
		given(service.findMine(42L)).willThrow(new AccessDeniedException("Employer role required"));

		assertThatThrownBy(() -> api.mine(principal(42L))).isInstanceOf(AccessDeniedException.class);
	}

	@Test
	void endpointHasNoRequestParameterForChoosingTheEmployer() throws Exception {
		// 메서드 인자는 인증 주체 하나뿐이라 employerId 같은 요청값으로 조회 대상을 바꿀 수 없다.
		Method mine = MyJobPostingApi.class.getMethod("mine", FarmPrincipal.class);

		assertThat(mine.getParameterCount()).isEqualTo(1);
		assertThat(mine.getParameters()[0].isAnnotationPresent(AuthenticationPrincipal.class)).isTrue();
		GetMapping mapping = mine.getAnnotation(GetMapping.class);
		assertThat(mapping.value()).containsExactly("/api/job-postings/mine");
		assertThat(mapping.produces()).containsExactly("application/json;charset=UTF-8");
	}

	private static FarmPrincipal principal(long memberId) {
		FarmPrincipal principal = mock(FarmPrincipal.class);
		given(principal.memberId()).willReturn(memberId);
		return principal;
	}
}
