package com.wonday.insurance.claim;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface ClaimWeatherStationRepository extends JpaRepository<ClaimWeatherStation, Long> {
	@Query("""
		select s.claimCaseId as claimCaseId, s.stationId as stationId, w.provider as provider,
		w.stationCode as stationCode, w.name as name, w.address as address, w.latitude as latitude, w.longitude as longitude,
		s.distanceMeters as distanceMeters, s.selectionRule as selectionRule, s.selectedAt as selectedAt
		from ClaimWeatherStation s join WeatherStation w on w.id=s.stationId where s.claimCaseId=:claimId
		""")
	Optional<SelectionRow> selection(long claimId);
	interface SelectionRow {
		long getClaimCaseId(); long getStationId(); String getProvider(); String getStationCode(); String getName(); String getAddress();
		BigDecimal getLatitude(); BigDecimal getLongitude(); BigDecimal getDistanceMeters(); String getSelectionRule(); LocalDateTime getSelectedAt();
	}
}
