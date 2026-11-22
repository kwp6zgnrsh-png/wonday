-- Insurance owns its schema. Platform worker and assignment IDs are external identifiers.
CREATE TABLE member (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    login_id VARCHAR(100) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    name VARCHAR(100) NOT NULL,
    role VARCHAR(32) NOT NULL,
    UNIQUE KEY uq_member_login (login_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE platform_link (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    member_id BIGINT NOT NULL,
    platform_worker_id BIGINT NOT NULL,
    consent_version VARCHAR(64) NOT NULL,
    consented_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6),
    UNIQUE KEY uq_link_owner (id, member_id),
    -- NULL allows multiple historical consents after revocation.
    UNIQUE KEY uq_active_link (member_id, platform_worker_id, ((CASE WHEN revoked_at IS NULL THEN 1 END))),
    FOREIGN KEY (member_id) REFERENCES member (id),
    CHECK (revoked_at IS NULL OR revoked_at >= consented_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE insurance_product (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    product_code VARCHAR(64) NOT NULL,
    name VARCHAR(150) NOT NULL,
    description TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    UNIQUE KEY uq_product_code (product_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE product_version (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    product_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    sales_start_at DATETIME(6) NOT NULL,
    sales_end_at DATETIME(6),
    terms_text LONGTEXT NOT NULL,
    eligible_work_groups_json JSON NOT NULL,
    index_rules_json JSON NOT NULL,
    payout_rules_json JSON NOT NULL,
    UNIQUE KEY uq_product_version (product_id, version_no),
    FOREIGN KEY (product_id) REFERENCES insurance_product (id),
    CHECK (version_no > 0),
    CHECK (sales_end_at IS NULL OR sales_end_at > sales_start_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE weather_station (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    provider VARCHAR(64) NOT NULL,
    station_code VARCHAR(40) NOT NULL,
    name VARCHAR(150) NOT NULL,
    latitude DECIMAL(9,6) NOT NULL,
    longitude DECIMAL(9,6) NOT NULL,
    UNIQUE KEY uq_station_code (provider, station_code),
    CHECK (latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE insurance_contract (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    member_id BIGINT NOT NULL,
    platform_link_id BIGINT NOT NULL,
    product_version_id BIGINT NOT NULL,
    platform_assignment_id BIGINT NOT NULL,
    agreed_crop_code VARCHAR(40) NOT NULL,
    agreed_work_type_code VARCHAR(40) NOT NULL,
    agreed_work_group VARCHAR(64) NOT NULL,
    agreed_work_location VARCHAR(500) NOT NULL,
    agreed_start_at DATETIME(6) NOT NULL,
    agreed_end_at DATETIME(6) NOT NULL,
    agreed_daily_wage BIGINT NOT NULL,
    station_id BIGINT NOT NULL,
    weather_window_start_at DATETIME(6) NOT NULL,
    weather_window_end_at DATETIME(6) NOT NULL,
    accepted_at DATETIME(6) NOT NULL,
    status VARCHAR(32) NOT NULL,
    UNIQUE KEY uq_contract_assignment (platform_assignment_id),
    FOREIGN KEY (member_id) REFERENCES member (id),
    FOREIGN KEY (platform_link_id, member_id) REFERENCES platform_link (id, member_id),
    FOREIGN KEY (product_version_id) REFERENCES product_version (id),
    FOREIGN KEY (station_id) REFERENCES weather_station (id),
    CHECK (agreed_daily_wage > 0 AND agreed_start_at < agreed_end_at),
    CHECK (weather_window_start_at < weather_window_end_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE weather_observation (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    station_id BIGINT NOT NULL,
    observed_at DATETIME(6) NOT NULL,
    temperature_c DECIMAL(6,2),
    max_temperature_c DECIMAL(6,2),
    humidity_pct DECIMAL(5,2),
    rainfall_mm DECIMAL(8,2),
    wind_speed_ms DECIMAL(6,2),
    max_wind_speed_ms DECIMAL(6,2),
    quality_status VARCHAR(32) NOT NULL,
    collected_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_observation_station_time (station_id, observed_at),
    FOREIGN KEY (station_id) REFERENCES weather_station (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE platform_work_event (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    contract_id BIGINT NOT NULL,
    source_event_key VARCHAR(160) NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    reason_code VARCHAR(40) NOT NULL,
    reason_detail TEXT,
    source_updated_at DATETIME(6) NOT NULL,
    received_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_event_source (source_event_key),
    UNIQUE KEY uq_event_contract (id, contract_id),
    FOREIGN KEY (contract_id) REFERENCES insurance_contract (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE claim_case (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    contract_id BIGINT NOT NULL,
    trigger_event_id BIGINT,
    opened_via VARCHAR(32) NOT NULL,
    status VARCHAR(40) NOT NULL,
    current_assessment_id BIGINT,
    opened_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_case_contract (contract_id),
    FOREIGN KEY (contract_id) REFERENCES insurance_contract (id),
    FOREIGN KEY (trigger_event_id, contract_id) REFERENCES platform_work_event (id, contract_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE claim_submission (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    claim_case_id BIGINT NOT NULL,
    submitted_by BIGINT NOT NULL,
    submission_type VARCHAR(32) NOT NULL,
    reason_text TEXT NOT NULL,
    attachments_json JSON,
    submitted_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    FOREIGN KEY (claim_case_id) REFERENCES claim_case (id),
    FOREIGN KEY (submitted_by) REFERENCES member (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE risk_assessment (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    claim_case_id BIGINT NOT NULL,
    revision_no INT NOT NULL,
    work_event_id BIGINT,
    weather_features_json JSON NOT NULL,
    data_quality_json JSON NOT NULL,
    r1_score DECIMAL(7,4) NOT NULL,
    r2_score DECIMAL(7,4) NOT NULL,
    r1_payout_rate DECIMAL(7,6) NOT NULL,
    r2_payout_rate DECIMAL(7,6) NOT NULL,
    applied_payout_rate DECIMAL(7,6) NOT NULL,
    calculated_amount BIGINT NOT NULL,
    calculated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_assessment_revision (claim_case_id, revision_no),
    UNIQUE KEY uq_assessment_case (id, claim_case_id),
    FOREIGN KEY (claim_case_id) REFERENCES claim_case (id),
    FOREIGN KEY (work_event_id) REFERENCES platform_work_event (id),
    CHECK (revision_no > 0 AND calculated_amount >= 0),
    CHECK (r1_score BETWEEN 0 AND 100 AND r2_score BETWEEN 0 AND 100),
    CHECK (r1_payout_rate BETWEEN 0 AND 1 AND r2_payout_rate BETWEEN 0 AND 1),
    CHECK (applied_payout_rate = GREATEST(r1_payout_rate, r2_payout_rate))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- A case is opened first and can later select only one of its own assessments.
ALTER TABLE claim_case
    ADD CONSTRAINT fk_case_current_assessment
    FOREIGN KEY (current_assessment_id, id) REFERENCES risk_assessment (id, claim_case_id);

CREATE TABLE claim_history (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    claim_case_id BIGINT NOT NULL,
    assessment_id BIGINT,
    actor_id BIGINT,
    action VARCHAR(48) NOT NULL,
    from_status VARCHAR(40),
    to_status VARCHAR(40) NOT NULL,
    reason TEXT,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    FOREIGN KEY (claim_case_id) REFERENCES claim_case (id),
    FOREIGN KEY (assessment_id, claim_case_id) REFERENCES risk_assessment (id, claim_case_id),
    FOREIGN KEY (actor_id) REFERENCES member (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE insurance_payout (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    claim_case_id BIGINT NOT NULL,
    approved_assessment_id BIGINT NOT NULL,
    amount BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    paid_at DATETIME(6),
    UNIQUE KEY uq_payout_case (claim_case_id),
    FOREIGN KEY (claim_case_id) REFERENCES claim_case (id),
    FOREIGN KEY (approved_assessment_id, claim_case_id) REFERENCES risk_assessment (id, claim_case_id),
    CHECK (amount >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_analysis_job (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    assessment_id BIGINT NOT NULL,
    model_version VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    rain_wind_anomaly_score DECIMAL(7,4),
    heat_anomaly_score DECIMAL(7,4),
    attempt_count INT NOT NULL DEFAULT 0,
    requested_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at DATETIME(6),
    error_code VARCHAR(64),
    UNIQUE KEY uq_analysis_model (assessment_id, model_version),
    FOREIGN KEY (assessment_id) REFERENCES risk_assessment (id),
    CHECK (rain_wind_anomaly_score BETWEEN 0 AND 100 AND heat_anomaly_score BETWEEN 0 AND 100),
    CHECK (attempt_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE outbox_event (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    job_id BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload_json JSON NOT NULL,
    publish_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    published_at DATETIME(6),
    KEY ix_outbox_due (publish_status, next_attempt_at),
    FOREIGN KEY (job_id) REFERENCES ai_analysis_job (id),
    CHECK (attempt_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
