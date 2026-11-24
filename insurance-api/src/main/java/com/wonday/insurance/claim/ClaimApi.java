package com.wonday.insurance.claim;

import com.wonday.insurance.member.MemberPrincipal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/claims")
@RequiredArgsConstructor
class ClaimApi {
	private final ClaimQueryService claims;

	@GetMapping
	List<ClaimQueryService.ClaimSummary> list(@AuthenticationPrincipal MemberPrincipal member) {
		return claims.list(member.memberId());
	}

	@GetMapping("/{claimId}")
	ClaimQueryService.ClaimDetail detail(@AuthenticationPrincipal MemberPrincipal member, @PathVariable String claimId) {
		if (!claimId.matches("[1-9][0-9]*")) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid claim ID");
		}
		try {
			return claims.detail(member.memberId(), Long.parseLong(claimId));
		} catch (NumberFormatException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Claim ID exceeds the supported range");
		}
	}
}
