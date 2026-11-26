package com.wonday.insurance.reward;

import com.wonday.insurance.member.MemberAccessService;
import com.wonday.insurance.platform.SalaryAccountQueryService;
import com.wonday.insurance.platform.SalaryAccountQueryService.Observation;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
class SalaryRewardWriter {

	private final MemberAccessService members;
	private final SalaryAccountQueryService accounts;
	private final SalaryRewardCheckRepository checks;
	private final SalaryRewardRule rule;
	private final Clock clock;

	@Transactional
	public SalaryRewardView record(long memberId, String requestKey, Observation observation, LocalDateTime checkedAt) {
		members.lockWorker(memberId);
		// 동시 요청이 외부 조회를 각각 수행했어도 같은 키의 저장·응답은 한 건으로 직렬화한다.
		var existing = checks.findByMemberIdAndRequestKey(memberId, requestKey);
		if (existing.isPresent()) return SalaryRewardView.from(existing.get());
		accounts.lockVerifiedLink(memberId, observation.source());
		LocalDateTime recordedAt = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
		return SalaryRewardView.from(checks.saveAndFlush(new SalaryRewardCheck(memberId, requestKey, observation.source(),
			rule.evaluate(observation), checkedAt, recordedAt)));
	}
}
