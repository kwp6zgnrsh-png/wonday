package com.wonday.farm.application.repository;

import java.util.Optional;
import com.wonday.farm.application.entity.ApplicationConditionAcknowledgement;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApplicationConditionAcknowledgementRepository
        extends JpaRepository<ApplicationConditionAcknowledgement, Long> {
    Optional<ApplicationConditionAcknowledgement> findByApplicationIdAndTermsVersion(long applicationId, int termsVersion);
}
