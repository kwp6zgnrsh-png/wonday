package com.wonday.insurance.weather;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface WeatherStationRepository extends JpaRepository<WeatherStation, Long> {
	List<WeatherStation> findByProviderAndEnabledTrueOrderByStationCode(String provider);
	Optional<WeatherStation> findByProviderAndStationCode(String provider, String code);
}
