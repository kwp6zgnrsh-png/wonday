ALTER TABLE member
    ADD COLUMN phone VARCHAR(32) NULL COMMENT 'Contact phone retained after product enrollment',
    ADD COLUMN resident_registration_number VARCHAR(32) NULL COMMENT 'Synthetic resident registration number for demo enrollment';
