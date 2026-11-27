-- 관측소 좌표 인증·모델 입력 규격과 무관하게, 확인된 관측소 코드와 날짜로 원본을 먼저 수집할 수 있다.
ALTER TABLE weather_station
    ADD COLUMN address VARCHAR(500) NULL,
    ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN metadata_synced_at DATETIME(6) NULL;

CREATE TABLE weather_collection (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    station_code VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    observation_date DATE NOT NULL,
    observation_count INT NOT NULL,
    payload_sha256 VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload_pages_json JSON NOT NULL,
    collected_at DATETIME(6) NOT NULL,
    UNIQUE KEY uq_weather_source (station_code, observation_date, payload_sha256),
    KEY ix_weather_collection_date (station_code, observation_date, id),
    CONSTRAINT ck_weather_count CHECK (observation_count BETWEEN 0 AND 144)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
