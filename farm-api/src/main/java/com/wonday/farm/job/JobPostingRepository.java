package com.wonday.farm.job;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface JobPostingRepository extends JpaRepository<JobPosting, Long> {
	List<JobPosting> findByEmployer_IdOrderByIdDesc(long employerId);
}
