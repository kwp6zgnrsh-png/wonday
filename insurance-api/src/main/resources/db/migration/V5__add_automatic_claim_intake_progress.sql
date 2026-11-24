-- Existing events start eligible independently of the database session timezone.
ALTER TABLE platform_work_event
    ADD COLUMN intake_processed_at DATETIME(6) NULL,
    ADD COLUMN intake_result VARCHAR(40) NULL,
    ADD COLUMN intake_next_attempt_at DATETIME(6) NOT NULL DEFAULT '1970-01-01 00:00:00.000000',
    ADD KEY ix_event_claim_intake (intake_processed_at, intake_next_attempt_at, id);
