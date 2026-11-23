-- V1 does not contain values for the new product terms or coverage periods.
-- Check for data requiring an explicit mapping before the first implicit DDL commit.
-- In V1 every contract, event, claim and assessment depends on a product version.
CREATE TEMPORARY TABLE v2_preflight (
    requires_resolution VARCHAR(80) PRIMARY KEY
);
INSERT INTO v2_preflight VALUES
    ('existing product versions require a V1 to V2 data mapping'),
    ('multiple active links for one insurance member'),
    ('multiple active links for one farm worker');

INSERT INTO v2_preflight
SELECT 'existing product versions require a V1 to V2 data mapping'
WHERE EXISTS (SELECT 1 FROM product_version);

INSERT INTO v2_preflight
SELECT 'multiple active links for one insurance member'
WHERE EXISTS (
    SELECT member_id FROM platform_link
    WHERE revoked_at IS NULL GROUP BY member_id HAVING COUNT(*) > 1
);

INSERT INTO v2_preflight
SELECT 'multiple active links for one farm worker'
WHERE EXISTS (
    SELECT platform_worker_id FROM platform_link
    WHERE revoked_at IS NULL GROUP BY platform_worker_id HAVING COUNT(*) > 1
);
DROP TEMPORARY TABLE v2_preflight;

-- Coverage belongs to a member's product contract, independent of a farm assignment.
ALTER TABLE insurance_contract
    DROP FOREIGN KEY insurance_contract_ibfk_2,
    DROP FOREIGN KEY insurance_contract_ibfk_4,
    DROP CHECK insurance_contract_chk_1,
    DROP CHECK insurance_contract_chk_2,
    DROP INDEX uq_contract_assignment,
    DROP COLUMN platform_link_id,
    DROP COLUMN platform_assignment_id,
    DROP COLUMN agreed_crop_code,
    DROP COLUMN agreed_work_type_code,
    DROP COLUMN agreed_work_group,
    DROP COLUMN agreed_work_location,
    DROP COLUMN agreed_start_at,
    DROP COLUMN agreed_end_at,
    DROP COLUMN agreed_daily_wage,
    DROP COLUMN station_id,
    DROP COLUMN weather_window_start_at,
    DROP COLUMN weather_window_end_at,
    ADD COLUMN coverage_from DATETIME(6) NOT NULL AFTER accepted_at,
    ADD COLUMN coverage_to DATETIME(6) NOT NULL AFTER coverage_from,
    ADD CONSTRAINT ck_contract_coverage CHECK (coverage_to > coverage_from);

-- Consent can be recorded before a farm identity has been verified.
ALTER TABLE platform_link
    MODIFY COLUMN platform_worker_id BIGINT NULL,
    DROP INDEX uq_link_owner,
    DROP INDEX uq_active_link,
    ADD UNIQUE KEY uq_active_member (member_id, ((CASE WHEN revoked_at IS NULL THEN 1 END))),
    ADD UNIQUE KEY uq_active_worker (platform_worker_id, ((CASE WHEN revoked_at IS NULL THEN 1 END)));

ALTER TABLE product_version
    ADD COLUMN premium_terms JSON NOT NULL AFTER terms_text,
    ADD COLUMN station_selection_rule JSON NOT NULL AFTER eligible_work_groups_json,
    ADD COLUMN window_rule JSON NOT NULL AFTER station_selection_rule;

-- Unbind the V1 event/contract relationship before changing the event key.
ALTER TABLE claim_case
    DROP FOREIGN KEY claim_case_ibfk_2,
    DROP FOREIGN KEY fk_case_current_assessment,
    DROP COLUMN current_assessment_id;

ALTER TABLE platform_work_event
    DROP FOREIGN KEY platform_work_event_ibfk_1,
    DROP INDEX uq_event_source,
    DROP INDEX uq_event_contract,
    DROP COLUMN contract_id,
    DROP COLUMN source_event_key,
    ADD COLUMN platform_link_id BIGINT NOT NULL AFTER id,
    ADD COLUMN source_change_id BIGINT NOT NULL AFTER platform_link_id,
    ADD COLUMN source_version VARCHAR(100) NOT NULL AFTER source_change_id,
    ADD COLUMN platform_assignment_id BIGINT NOT NULL AFTER source_version,
    ADD COLUMN work_snapshot JSON,
    ADD UNIQUE KEY uq_event_source (source_change_id, source_version),
    ADD UNIQUE KEY uq_event_assignment (id, platform_assignment_id),
    ADD FOREIGN KEY (platform_link_id) REFERENCES platform_link (id);

-- Automatic and manual intake share one case per contract and assignment.
ALTER TABLE claim_case
    ADD COLUMN platform_assignment_id BIGINT NOT NULL AFTER contract_id,
    MODIFY COLUMN trigger_event_id BIGINT NOT NULL,
    ADD COLUMN work_snapshot JSON NOT NULL AFTER opened_via,
    DROP INDEX uq_case_contract,
    ADD UNIQUE KEY uq_case_contract_assignment (contract_id, platform_assignment_id),
    ADD FOREIGN KEY (trigger_event_id, platform_assignment_id)
        REFERENCES platform_work_event (id, platform_assignment_id);

-- Keep the station, time window and versioned rule output with the assessment.
ALTER TABLE risk_assessment DROP FOREIGN KEY risk_assessment_ibfk_2;

ALTER TABLE risk_assessment
    DROP CHECK risk_assessment_chk_2,
    DROP CHECK risk_assessment_chk_3,
    DROP CHECK risk_assessment_chk_4,
    MODIFY COLUMN work_event_id BIGINT NOT NULL,
    ADD COLUMN station_id BIGINT NOT NULL AFTER work_event_id,
    ADD COLUMN window_start_at DATETIME(6) NOT NULL AFTER station_id,
    ADD COLUMN window_end_at DATETIME(6) NOT NULL AFTER window_start_at,
    DROP COLUMN r1_score,
    DROP COLUMN r2_score,
    DROP COLUMN r1_payout_rate,
    DROP COLUMN r2_payout_rate,
    DROP COLUMN applied_payout_rate,
    ADD COLUMN rule_version VARCHAR(64) NOT NULL AFTER data_quality_json,
    ADD COLUMN index_results_json JSON NOT NULL AFTER rule_version,
    ADD COLUMN applied_rates_json JSON AFTER index_results_json,
    ADD COLUMN decision_reason TEXT NOT NULL AFTER calculated_amount,
    MODIFY COLUMN calculated_at DATETIME(6) NOT NULL,
    ADD FOREIGN KEY (work_event_id) REFERENCES platform_work_event (id),
    ADD FOREIGN KEY (station_id) REFERENCES weather_station (id),
    ADD CONSTRAINT ck_assessment_window CHECK (window_end_at > window_start_at);
