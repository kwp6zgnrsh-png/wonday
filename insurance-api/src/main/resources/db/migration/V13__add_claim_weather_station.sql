-- 관측소는 접수별로 한 번 선정한다. 근무 좌표의 원본은 claim_case.work_snapshot에 있다.
CREATE TABLE claim_weather_station (
    claim_case_id BIGINT NOT NULL PRIMARY KEY,
    station_id BIGINT NOT NULL,
    distance_meters DECIMAL(12,3) NOT NULL,
    selection_rule VARCHAR(64) NOT NULL,
    selected_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_claim_station_case FOREIGN KEY (claim_case_id) REFERENCES claim_case(id) ON DELETE CASCADE,
    CONSTRAINT fk_claim_station_station FOREIGN KEY (station_id) REFERENCES weather_station(id),
    CONSTRAINT ck_claim_station_distance CHECK (distance_meters >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
