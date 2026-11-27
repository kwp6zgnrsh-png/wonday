package com.wonday.insurance.weather;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface WeatherCollectionRepository extends JpaRepository<WeatherCollection, Long> {
	Optional<WeatherCollection> findByStationCodeAndObservationDateAndPayloadSha256(String code, LocalDate date, String hash);
	List<WeatherCollection> findTop100ByStationCodeAndObservationDateOrderByIdDesc(String code, LocalDate date);
}
