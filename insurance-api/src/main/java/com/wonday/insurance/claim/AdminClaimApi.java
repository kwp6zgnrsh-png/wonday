package com.wonday.insurance.claim;

import com.wonday.insurance.member.MemberPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/claims")
@RequiredArgsConstructor
class AdminClaimApi {
	private final AdminClaimQueryService claims;
	private final ClaimWeatherStationService stations;
	@GetMapping("/{claimId}/weather-station")
	ResponseEntity<ClaimWeatherStationService.State> station(@AuthenticationPrincipal MemberPrincipal admin, @PathVariable String claimId) {
		return response(stations.get(admin.memberId(), claimId));
	}
	@PostMapping("/{claimId}/weather-station")
	ResponseEntity<ClaimWeatherStationView> selectStation(@AuthenticationPrincipal MemberPrincipal admin, @PathVariable String claimId) {
		return response(stations.select(admin.memberId(), claimId));
	}
	@GetMapping
	ResponseEntity<AdminClaimView.Page<AdminClaimView.Summary>> list(@AuthenticationPrincipal MemberPrincipal admin,
		@RequestParam(required=false) String status, @RequestParam(required=false) Long memberId,
		@RequestParam(required=false) Long beforeId, @RequestParam(defaultValue="20") int limit) {
		return response(claims.list(admin.memberId(),status,memberId,beforeId,limit));
	}
	@GetMapping("/{claimId}")
	ResponseEntity<AdminClaimView.Detail> detail(@AuthenticationPrincipal MemberPrincipal admin,@PathVariable String claimId) {
		return response(claims.detail(admin.memberId(),claimId));
	}
	@GetMapping("/{claimId}/history")
	ResponseEntity<AdminClaimView.Page<AdminClaimView.History>> history(@AuthenticationPrincipal MemberPrincipal admin,@PathVariable String claimId,
		@RequestParam(required=false) Long beforeId,@RequestParam(defaultValue="20") int limit) {
		return response(claims.history(admin.memberId(),claimId,beforeId,limit));
	}
	private static <T> ResponseEntity<T> response(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
}
