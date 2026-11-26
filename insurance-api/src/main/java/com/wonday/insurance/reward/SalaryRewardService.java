package com.wonday.insurance.reward;

import com.wonday.insurance.member.MemberAccessService;
import com.wonday.insurance.platform.SalaryAccountQueryService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
class SalaryRewardService {

	private final MemberAccessService members;
	private final SalaryRewardCheckRepository checks;
	private final SalaryAccountQueryService accounts;
	private final SalaryRewardWriter writer;
	private final Clock clock;

	SalaryRewardView check(long memberId, String rawKey) {
		String key = normalizeKey(rawKey);
		members.requireWorker(memberId);
		var existing = checks.findByMemberIdAndRequestKey(memberId, key);
		if (existing.isPresent()) return SalaryRewardView.from(existing.get());
		// 외부 조회는 쓰기 트랜잭션 전에 끝낸다. 예상된 조회 실패도 별도 판정 이력으로 남긴다.
		var observation = accounts.observe(memberId);
		LocalDateTime checkedAt = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
		return writer.record(memberId, key, observation, checkedAt);
	}

	private static String normalizeKey(String raw) {
		if (raw == null || !raw.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key must be a canonical UUID");
		}
		return UUID.fromString(raw).toString();
	}
}
