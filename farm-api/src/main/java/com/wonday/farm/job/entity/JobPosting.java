package com.wonday.farm.job.entity;

import com.wonday.farm.job.dto.CreateJobPostingRequest;
import com.wonday.farm.member.Member;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "job_posting")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JobPosting {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "employer_id", nullable = false)
	private Member employer;

	@Column(nullable = false, length = 200)
	private String title;

	@Column(nullable = false, columnDefinition = "TEXT")
	private String description;

	@Column(name = "crop_code", nullable = false, length = 40)
	private String cropCode;

	@Column(name = "work_type_code", nullable = false, length = 40)
	private String workTypeCode;

	@Column(name = "work_location", nullable = false, length = 500)
	private String workLocation;

	@Column(name = "time_zone", length = 64)
	private String timeZone;

	// 기존 DB 컬럼명을 유지하며 API에서는 날짜마다 적용할 공통 정원·일급으로 제공한다.
	@Column(name = "default_headcount", nullable = false)
	private int headcount;

	@Column(name = "default_daily_wage", nullable = false)
	private long dailyWage;

	public JobPosting(Member employer, CreateJobPostingRequest request) {
		this.employer = employer;
		title = request.title();
		description = request.description();
		cropCode = request.cropCode();
		workTypeCode = request.workTypeCode();
		workLocation = request.workLocation();
		timeZone = request.timeZone();
		headcount = request.headcount();
		dailyWage = request.dailyWage();
	}
}
