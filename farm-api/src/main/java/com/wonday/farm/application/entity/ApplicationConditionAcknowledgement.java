package com.wonday.farm.application.entity;

import java.time.LocalDateTime;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

// 새 회차로 재지원해도 이 기록을 옮기지 않는다. 같은 지원·버전의 최초 확인은 변경하지 않는다.
@Entity
@Table(name = "application_condition_acknowledgement", uniqueConstraints = @UniqueConstraint(
    name = "uq_application_acknowledgement_version", columnNames = {"application_id", "terms_version"}))
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ApplicationConditionAcknowledgement {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "application_id", nullable = false)
    private long applicationId;
    @Column(name = "terms_version", nullable = false)
    private int termsVersion;
    @Column(name = "acknowledged_at_utc", nullable = false)
    private LocalDateTime acknowledgedAtUtc;
    @Column(name = "conditions_payload", nullable = false, columnDefinition = "LONGTEXT")
    private String conditionsPayload;

    public ApplicationConditionAcknowledgement(long applicationId, int termsVersion,
            LocalDateTime acknowledgedAtUtc, String conditionsPayload) {
        if (applicationId <= 0 || termsVersion <= 0 || acknowledgedAtUtc == null
                || conditionsPayload == null || conditionsPayload.isBlank()) {
            throw new IllegalArgumentException("Invalid acknowledgement");
        }
        this.applicationId = applicationId;
        this.termsVersion = termsVersion;
        this.acknowledgedAtUtc = acknowledgedAtUtc;
        this.conditionsPayload = conditionsPayload;
    }
}
