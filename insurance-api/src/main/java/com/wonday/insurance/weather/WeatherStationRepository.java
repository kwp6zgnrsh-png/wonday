package com.wonday.insurance.weather;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface WeatherStationRepository extends JpaRepository<WeatherStation, Long> {
	List<WeatherStation> findByProviderAndEnabledTrueOrderByStationCode(String provider);
	// UNIQUE(provider, station_code)를 기준으로 DB가 삽입/갱신을 결정한다. 최초 동기화끼리도 INSERT 경쟁으로 실패하지 않는다.
	@Modifying
	@Query(value = """
		INSERT INTO weather_station (provider, station_code, name, address, latitude, longitude, enabled, metadata_synced_at)
		VALUES ('RDA', :code, :name, :address, :latitude, :longitude, true, :syncedAt) AS incoming
		ON DUPLICATE KEY UPDATE name = incoming.name, address = incoming.address,
			latitude = incoming.latitude, longitude = incoming.longitude, enabled = incoming.enabled,
			metadata_synced_at = incoming.metadata_synced_at
		""", nativeQuery = true)
	void upsert(@Param("code") String code, @Param("name") String name, @Param("address") String address,
		@Param("latitude") BigDecimal latitude, @Param("longitude") BigDecimal longitude, @Param("syncedAt") LocalDateTime syncedAt);
}
