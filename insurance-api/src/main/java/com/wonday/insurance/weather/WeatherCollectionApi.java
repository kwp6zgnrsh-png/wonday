package com.wonday.insurance.weather;

import com.wonday.insurance.member.MemberPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/weather/collections")
@RequiredArgsConstructor
class WeatherCollectionApi {
	private final WeatherCollectionService collections;
	@PostMapping
	ResponseEntity<WeatherCollectionView> collect(@AuthenticationPrincipal MemberPrincipal member, @Valid @RequestBody Request request,
		@RequestHeader(value = "Idempotency-Key", required = false) String key) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(collections.collect(member.memberId(), request.stationCode(), request.date(), key));
	}
	@GetMapping
	ResponseEntity<List<WeatherCollectionView>> list(@AuthenticationPrincipal MemberPrincipal member, @RequestParam String stationCode, @RequestParam LocalDate date) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(collections.list(member.memberId(), stationCode, date));
	}
	@GetMapping("/{id}")
	ResponseEntity<WeatherCollectionService.Detail> detail(@AuthenticationPrincipal MemberPrincipal member, @PathVariable long id) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(collections.detail(member.memberId(), id));
	}
	record Request(@NotBlank String stationCode, @NotNull LocalDate date) {}
}
