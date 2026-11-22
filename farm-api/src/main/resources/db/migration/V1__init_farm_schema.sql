CREATE TABLE member (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    login_id VARCHAR(100) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    name VARCHAR(100) NOT NULL,
    phone VARCHAR(32) NOT NULL,
    role VARCHAR(32) NOT NULL,
    UNIQUE KEY uq_member_login (login_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE job_posting (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    employer_id BIGINT NOT NULL,
    title VARCHAR(200) NOT NULL,
    description TEXT NOT NULL,
    crop_code VARCHAR(40) NOT NULL,
    work_type_code VARCHAR(40) NOT NULL,
    work_location VARCHAR(500) NOT NULL,
    default_headcount INT NOT NULL,
    default_daily_wage BIGINT NOT NULL,
    FOREIGN KEY (employer_id) REFERENCES member (id),
    CHECK (default_headcount > 0 AND default_daily_wage > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE work_schedule (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    job_posting_id BIGINT NOT NULL,
    work_date DATE NOT NULL,
    planned_start_at DATETIME(6) NOT NULL,
    planned_end_at DATETIME(6) NOT NULL,
    break_start_at DATETIME(6),
    break_end_at DATETIME(6),
    status VARCHAR(32) NOT NULL,
    cancelled_at DATETIME(6),
    actual_end_at DATETIME(6),
    reason_code VARCHAR(40),
    reason_detail TEXT,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_posting_work_date (job_posting_id, work_date),
    KEY ix_schedule_poll (updated_at, id),
    FOREIGN KEY (job_posting_id) REFERENCES job_posting (id),
    CHECK (planned_end_at > planned_start_at),
    CHECK ((break_start_at IS NULL AND break_end_at IS NULL) OR
           (break_start_at IS NOT NULL AND break_end_at IS NOT NULL AND
            break_start_at >= planned_start_at AND break_end_at <= planned_end_at AND
            break_end_at > break_start_at))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE application (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    work_schedule_id BIGINT NOT NULL,
    worker_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    applied_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_application_schedule_worker (work_schedule_id, worker_id),
    FOREIGN KEY (work_schedule_id) REFERENCES work_schedule (id),
    FOREIGN KEY (worker_id) REFERENCES member (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE work_assignment (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    application_id BIGINT NOT NULL,
    confirmed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    agreed_crop_code VARCHAR(40) NOT NULL,
    agreed_work_type_code VARCHAR(40) NOT NULL,
    agreed_start_at DATETIME(6) NOT NULL,
    agreed_end_at DATETIME(6) NOT NULL,
    agreed_break_start_at DATETIME(6),
    agreed_break_end_at DATETIME(6),
    agreed_daily_wage BIGINT NOT NULL,
    agreed_work_location VARCHAR(500) NOT NULL,
    UNIQUE KEY uq_assignment_application (application_id),
    FOREIGN KEY (application_id) REFERENCES application (id),
    CHECK (agreed_end_at > agreed_start_at AND agreed_daily_wage > 0),
    CHECK ((agreed_break_start_at IS NULL AND agreed_break_end_at IS NULL) OR
           (agreed_break_start_at IS NOT NULL AND agreed_break_end_at IS NOT NULL AND
            agreed_break_start_at >= agreed_start_at AND agreed_break_end_at <= agreed_end_at AND
            agreed_break_end_at > agreed_break_start_at))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
