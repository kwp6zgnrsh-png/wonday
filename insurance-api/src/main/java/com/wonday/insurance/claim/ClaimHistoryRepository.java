package com.wonday.insurance.claim;

import org.springframework.data.jpa.repository.JpaRepository;

interface ClaimHistoryRepository extends JpaRepository<ClaimHistory, Long> {}
