package com.wonday.insurance.weather;

import com.wonday.insurance.member.MemberPrincipal;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/weather/collection-jobs")
@RequiredArgsConstructor
class WeatherCollectionJobApi {
	private final WeatherCollectionJobService jobs;
	private final WeatherCollectionService collections;
	@GetMapping
	ResponseEntity<WeatherCollectionJobService.Page<WeatherCollectionJobService.JobView>> list(@AuthenticationPrincipal MemberPrincipal member,
		@RequestParam(required = false) String state, @RequestParam(required = false) String stationCode,
		@RequestParam(required = false) LocalDate date, @RequestParam(required = false) Long beforeId, @RequestParam(defaultValue = "20") int limit) {
		return response(jobs.list(member.memberId(), state, stationCode, date, beforeId, limit));
	}
	@GetMapping("/{id}")
	ResponseEntity<WeatherCollectionJobService.JobView> detail(@AuthenticationPrincipal MemberPrincipal member, @PathVariable long id) {
		return response(jobs.detail(member.memberId(), id));
	}
	@GetMapping("/{id}/attempts")
	ResponseEntity<WeatherCollectionJobService.Page<WeatherCollectionJobService.AttemptView>> attempts(@AuthenticationPrincipal MemberPrincipal member,
		@PathVariable long id, @RequestParam(required = false) Long beforeId, @RequestParam(defaultValue = "20") int limit) {
		return response(jobs.attempts(member.memberId(), id, beforeId, limit));
	}
	@PostMapping("/{id}/retry")
	ResponseEntity<WeatherCollectionView> retry(@AuthenticationPrincipal MemberPrincipal member, @PathVariable long id,
		@RequestHeader(value = "Idempotency-Key", required = false) String key) {
		return response(collections.retry(member.memberId(), id, key));
	}
	private static <T> ResponseEntity<T> response(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
}
