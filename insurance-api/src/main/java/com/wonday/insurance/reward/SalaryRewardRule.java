package com.wonday.insurance.reward;

import com.wonday.insurance.platform.SalaryAccountQueryService.Observation;
import org.springframework.stereotype.Component;

@Component
public class SalaryRewardRule {

	public static final String VERSION = "salary-woori-v1";
	public enum Result { ELIGIBLE, INELIGIBLE, UNAVAILABLE }
	public enum Reason { WOORI_SALARY_ACCOUNT, OTHER_BANK, NO_SALARY_ACCOUNT, QUERY_FAILED }
	public enum SourceError { FARM_SERVICE_UNAVAILABLE, WORKER_NOT_FOUND, INVALID_PLATFORM_RESPONSE, SALARY_ACCOUNT_QUERY_NOT_READY }

	Decision evaluate(Observation observation) {
		if (observation.failure() != null) {
			SourceError error;
			try {
				error = SourceError.valueOf(observation.failure().getCode());
			} catch (IllegalArgumentException | NullPointerException exception) {
				error = SourceError.FARM_SERVICE_UNAVAILABLE;
			}
			// 실패의 등록 여부는 null이다. 미등록/타 은행이라는 의미로 저장하지 않는다.
			return new Decision(Result.UNAVAILABLE, Reason.QUERY_FAILED, null, null, error);
		}
		var account = observation.account();
		if (!account.registered()) return new Decision(Result.INELIGIBLE, Reason.NO_SALARY_ACCOUNT, false, null, null);
		if ("020".equals(account.bankCode())) return new Decision(Result.ELIGIBLE, Reason.WOORI_SALARY_ACCOUNT, true, "020", null);
		return new Decision(Result.INELIGIBLE, Reason.OTHER_BANK, true, account.bankCode(), null);
	}

	record Decision(Result result, Reason reason, Boolean registered, String bankCode, SourceError error) {}
}
