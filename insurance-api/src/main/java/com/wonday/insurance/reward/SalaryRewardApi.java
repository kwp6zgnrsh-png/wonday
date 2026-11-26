package com.wonday.insurance.reward;

import com.wonday.insurance.member.MemberPrincipal;
import com.wonday.insurance.platform.PlatformVerificationException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/rewards/salary-account")
@RequiredArgsConstructor
class SalaryRewardApi {

	private final SalaryRewardService rewards;
	private final SalaryRewardQueryService queries;

	@PostMapping("/checks")
	ResponseEntity<SalaryRewardView> check(@AuthenticationPrincipal MemberPrincipal member,
			@RequestHeader("Idempotency-Key") String key) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(rewards.check(member.memberId(), key));
	}

	@GetMapping("/latest")
	ResponseEntity<SalaryRewardView> latest(@AuthenticationPrincipal MemberPrincipal member) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(queries.latest(member.memberId()));
	}

	@GetMapping("/checks")
	ResponseEntity<SalaryRewardQueryService.History> history(@AuthenticationPrincipal MemberPrincipal member,
			@RequestParam(defaultValue = "20") int limit, @RequestParam(required = false) Long beforeId) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(queries.history(member.memberId(), limit, beforeId));
	}

	@ExceptionHandler(PlatformVerificationException.class)
	ResponseEntity<RewardError> failed(PlatformVerificationException exception) {
		return ResponseEntity.status(exception.getStatus()).cacheControl(CacheControl.noStore())
			.body(new RewardError(exception.getCode(), exception.getMessage()));
	}

	record RewardError(String code, String message) {}
}
