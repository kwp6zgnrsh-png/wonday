package com.wonday.insurance.weather;

import com.wonday.insurance.member.MemberPrincipal;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
class WeatherStationApi {
	private final WeatherStationService stations;
	@GetMapping("/api/weather/stations")
	ResponseEntity<List<WeatherStationService.StationView>> list(@AuthenticationPrincipal MemberPrincipal member) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(stations.list(member.memberId()));
	}
	@GetMapping("/api/weather/stations/nearest")
	ResponseEntity<WeatherStationService.Selection> nearest(@AuthenticationPrincipal MemberPrincipal member,
			@RequestParam BigDecimal latitude, @RequestParam BigDecimal longitude) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(stations.nearest(member.memberId(), latitude, longitude));
	}
}
