package com.wonday.farm.job.repository;

import java.util.List;

import com.wonday.farm.job.entity.JobPosting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobPostingRepository extends JpaRepository<JobPosting, Long> {
	List<JobPosting> findByEmployer_IdOrderByIdDesc(long employerId);
}
