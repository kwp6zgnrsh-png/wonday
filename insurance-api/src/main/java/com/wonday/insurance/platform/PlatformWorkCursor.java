package com.wonday.insurance.platform;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "platform_work_cursor")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class PlatformWorkCursor {
	@Id
	@Column(name = "platform_worker_id")
	private Long workerId;

	@Column(name = "cursor_value", columnDefinition = "TEXT")
	private String cursor;

	@JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	PlatformWorkCursor(long workerId, LocalDateTime now) {
		this.workerId = workerId;
		this.updatedAt = now;
	}

	void advance(String cursor, LocalDateTime now) {
		this.cursor = cursor;
		this.updatedAt = now;
	}
}
