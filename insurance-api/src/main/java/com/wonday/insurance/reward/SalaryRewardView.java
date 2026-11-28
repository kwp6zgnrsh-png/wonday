package com.wonday.insurance.reward;

import com.wonday.insurance.api.ApiTime;
import java.time.OffsetDateTime;
import static com.wonday.insurance.reward.SalaryRewardRule.*;

// 소유자·농업 근로자 ID와 요청 키는 응답하지 않는다. UNAVAILABLE의 registered는 false가 아니라 null이다.
record SalaryRewardView(long id, Result result, Reason reasonCode, Boolean registered, String bankCode,
		SourceError sourceErrorCode, String ruleVersion, OffsetDateTime checkedAt, OffsetDateTime recordedAt) {
	static SalaryRewardView from(SalaryRewardCheck check) {
		return new SalaryRewardView(check.getId(), check.getResult(), check.getReasonCode(), check.getRegistered(),
			check.getBankCode(), check.getSourceErrorCode(), check.getRuleVersion(),
			ApiTime.fromUtc(check.getCheckedAt()), ApiTime.fromUtc(check.getRecordedAt()));
	}
}
