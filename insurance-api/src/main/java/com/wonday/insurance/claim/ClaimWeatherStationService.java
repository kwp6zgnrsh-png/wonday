package com.wonday.insurance.claim;

import com.wonday.insurance.api.ApiTime;
import com.wonday.insurance.member.MemberAccessService;
import com.wonday.insurance.weather.WeatherStationCatalog;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
class ClaimWeatherStationService {
	private final MemberAccessService members;
	private final ClaimRepository claims;
	private final AdminClaimRepository visibleClaims;
	private final ClaimWeatherStationRepository selections;
	private final WeatherStationCatalog catalog;
	private final ObjectMapper json;
	private final Clock clock;

	@Transactional(readOnly = true)
	public State get(long adminId, String rawId) {
		members.requireAdmin(adminId);
		long claimId = AdminClaimQueryService.id(rawId);
		visibleClaims.visibleId(claimId).orElseThrow(AdminClaimQueryException::notFound);
		var selected = saved(claimId);
		return new State(selected != null, selected);
	}

	@Transactional
	public ClaimWeatherStationView select(long adminId, String rawId) {
		members.lockAdmin(adminId);
		long claimId = AdminClaimQueryService.id(rawId);
		// 서로 다른 관리자도 같은 접수 행을 잠가 최초 선정만 저장한다. UNIQUE PK는 DB에서도 중복을 막는다.
		var claim = claims.lockForStationSelection(claimId).orElseThrow(AdminClaimQueryException::notFound);
		var row = visibleClaims.detail(claimId).orElseThrow(AdminClaimQueryException::notFound);
		var existing = saved(claimId);
		if (existing != null) return existing;
		if (!claim.getStatus().equals("REVIEWING")) throw new AdminClaimQueryException(HttpStatus.CONFLICT,
			"CLAIM_NOT_REVIEWING", "Only reviewing claims can select a weather station");
		if (!persist(claim, row.getWorkerId())) throw new AdminClaimQueryException(HttpStatus.CONFLICT,
			"WORK_COORDINATES_UNAVAILABLE", "Stored work coordinates are unavailable");
		return saved(claimId);
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public boolean selectForIntake(ClaimCase claim) {
		return persist(claim, null); // 자동 접수에서는 PlatformClaimEvents.verifiedWork가 근로자·근무 소유 연결을 이미 검증했다.
	}

	private boolean persist(ClaimCase claim, Long expectedWorkerId) {
		if (selections.existsById(claim.getId())) return true;
		var coordinates = coordinates(claim, expectedWorkerId);
		// 기존 약정에 좌표가 없으면 자동 접수는 유지한다. 현재 공고/주소나 임의 관측소로 보완하지 않는다.
		if (coordinates == null) return false;
		var selected = catalog.nearest(coordinates.latitude(), coordinates.longitude());
		var now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
		selections.saveAndFlush(new ClaimWeatherStation(claim.getId(), selected, now));
		return true;
	}

	@Transactional(readOnly = true)
	public ClaimWeatherStationView saved(long claimId) {
		return selections.selection(claimId).map(row -> new ClaimWeatherStationView(row.getClaimCaseId(), row.getStationId(),
			row.getProvider(), row.getStationCode(), row.getName(), row.getAddress(), row.getLatitude(), row.getLongitude(),
			row.getDistanceMeters(), row.getSelectionRule(), ApiTime.fromUtc(row.getSelectedAt()))).orElse(null);
	}

	private Coordinates coordinates(ClaimCase claim, Long expectedWorkerId) {
		try {
			var work = json.readTree(claim.getWorkSnapshot());
			if (work == null || !work.isObject() || !work.path("assignmentId").isIntegralNumber()
				|| !work.path("assignmentId").canConvertToLong() || work.path("assignmentId").longValue() != claim.getAssignmentId()) {
				throw AdminClaimQueryException.invalidData();
			}
			if (expectedWorkerId != null && (!work.path("workerId").isIntegralNumber() || !work.path("workerId").canConvertToLong()
				|| work.path("workerId").longValue() != expectedWorkerId)) throw AdminClaimQueryException.invalidData();
			var latitude = work.get("agreedLatitude"); var longitude = work.get("agreedLongitude");
			boolean noLatitude = latitude == null || latitude.isNull(), noLongitude = longitude == null || longitude.isNull();
			if (noLatitude && noLongitude) return null;
			if (noLatitude || noLongitude || !latitude.isNumber() || !longitude.isNumber()) throw AdminClaimQueryException.invalidData();
			var lat = latitude.decimalValue(); var lon = longitude.decimalValue();
			if (lat.compareTo(BigDecimal.valueOf(-90)) < 0 || lat.compareTo(BigDecimal.valueOf(90)) > 0
				|| lon.compareTo(BigDecimal.valueOf(-180)) < 0 || lon.compareTo(BigDecimal.valueOf(180)) > 0) throw AdminClaimQueryException.invalidData();
			return new Coordinates(lat, lon);
		} catch (RuntimeException invalid) { throw AdminClaimQueryException.invalidData(); }
	}
	record State(boolean selected, ClaimWeatherStationView selection) {}
	private record Coordinates(BigDecimal latitude, BigDecimal longitude) {}
}
