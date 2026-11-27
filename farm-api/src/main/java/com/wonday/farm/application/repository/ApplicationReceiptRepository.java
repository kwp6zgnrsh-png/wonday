package com.wonday.farm.application.repository;

import java.util.Optional;
import com.wonday.farm.application.entity.ApplicationReceipt;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApplicationReceiptRepository extends JpaRepository<ApplicationReceipt, Long> {
	Optional<ApplicationReceipt> findByWorkerIdAndRequestKey(long workerId, String requestKey);
}