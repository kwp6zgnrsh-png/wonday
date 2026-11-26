package com.wonday.insurance.reward;

import static org.assertj.core.api.Assertions.*;
import static com.wonday.insurance.reward.SalaryRewardRule.*;

import com.wonday.insurance.platform.PlatformVerificationException;
import com.wonday.insurance.platform.SalaryAccountQueryService.Observation;
import com.wonday.insurance.platform.SalaryAccountQueryService.Snapshot;
import com.wonday.insurance.platform.SalaryAccountStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;

class SalaryRewardRuleTests {

	private final SalaryRewardRule rule = new SalaryRewardRule();
	private final Snapshot source = new Snapshot(1, 123, "farm-platform-v1");

	@ParameterizedTest
	@CsvSource({"true,020,ELIGIBLE,WOORI_SALARY_ACCOUNT", "true,004,INELIGIBLE,OTHER_BANK", "false,,INELIGIBLE,NO_SALARY_ACCOUNT"})
	void evaluatesOnlySalaryAccountRegistration(boolean registered, String bank, Result result, Reason reason) {
		var decision = rule.evaluate(new Observation(source, new SalaryAccountStatus(registered, bank), null));
		assertThat(decision.result()).isEqualTo(result);
		assertThat(decision.reason()).isEqualTo(reason);
		assertThat(decision.registered()).isEqualTo(registered);
		assertThat(decision.bankCode()).isEqualTo(bank);
		assertThat(decision.error()).isNull();
	}

	@ParameterizedTest
	@EnumSource(SourceError.class)
	void expectedFailuresRemainUnknownRatherThanIneligible(SourceError error) {
		var failure = new PlatformVerificationException(HttpStatus.SERVICE_UNAVAILABLE, error.name(), "private-upstream-message");
		var decision = rule.evaluate(new Observation(source, null, failure));
		assertThat(decision.result()).isEqualTo(Result.UNAVAILABLE);
		assertThat(decision.reason()).isEqualTo(Reason.QUERY_FAILED);
		assertThat(decision.registered()).isNull();
		assertThat(decision.bankCode()).isNull();
		assertThat(decision.error()).isEqualTo(error);
		assertThat(decision.toString()).doesNotContain("private-upstream-message");
	}

	@Test
	void unknownProviderCodesAreNotPersistedAsFreeText() {
		var failure = new PlatformVerificationException(HttpStatus.SERVICE_UNAVAILABLE, "private-code", "private-message");
		var decision = rule.evaluate(new Observation(source, null, failure));
		assertThat(decision.error()).isEqualTo(SourceError.FARM_SERVICE_UNAVAILABLE);
		assertThat(decision.toString()).doesNotContain("private-code", "private-message");
	}
}
