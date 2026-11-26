package com.wonday.farm.insurance.service;

import static com.wonday.farm.insurance.testsupport.InsuranceFixtures.member;
import static com.wonday.farm.insurance.testsupport.InsuranceFixtures.memberWithRoleCode;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Optional;

import com.wonday.farm.insurance.exception.InsuranceApiException;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

// 근로자 존재·현재 역할 확인. 동의·철회는 팀 합의상 보험 서버 책임이라 여기서 확인하지 않는다.
class InsuranceDataAccessTest {
	private final MemberRepository members = mock(MemberRepository.class);
	private final InsuranceDataAccess access = new InsuranceDataAccess(members);

	@ParameterizedTest(name = "workerId={0}")
	@ValueSource(longs = {0, -1, Long.MIN_VALUE})
	void nonPositiveIdIsInvalidRequestWithoutLookup(long workerId) {
		assertThatThrownBy(() -> access.requireWorker(workerId)).isInstanceOfSatisfying(InsuranceApiException.class,
			error -> org.assertj.core.api.Assertions.assertThat(error.code()).isEqualTo("INVALID_REQUEST"));
		verifyNoInteractions(members);
	}

	@Test
	void workerPasses() {
		given(members.findById(5L)).willReturn(Optional.of(member(5L, MemberRole.WORKER)));

		assertThatCode(() -> access.requireWorker(5L)).doesNotThrowAnyException();
	}

	@Test
	void missingMemberIsWorkerNotFound() {
		given(members.findById(5L)).willReturn(Optional.empty());

		assertWorkerNotFound(5L);
	}

	@Test
	void employerIsWorkerNotFound() {
		given(members.findById(5L)).willReturn(Optional.of(member(5L, MemberRole.EMPLOYER)));

		assertWorkerNotFound(5L);
	}

	@ParameterizedTest(name = "role={0}")
	@ValueSource(strings = {"ADMIN", "worker", "WORKER ", ""})
	void unsupportedRoleCodeIsWorkerNotFound(String role) {
		given(members.findById(5L)).willReturn(Optional.of(memberWithRoleCode(5L, role)));

		assertWorkerNotFound(5L);
	}

	private void assertWorkerNotFound(long workerId) {
		assertThatThrownBy(() -> access.requireWorker(workerId)).isInstanceOfSatisfying(InsuranceApiException.class,
			error -> {
				org.assertj.core.api.Assertions.assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND);
				org.assertj.core.api.Assertions.assertThat(error.code()).isEqualTo("WORKER_NOT_FOUND");
			});
	}
}
