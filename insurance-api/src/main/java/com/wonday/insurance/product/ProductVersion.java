package com.wonday.insurance.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "product_version")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class ProductVersion {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "product_id", nullable = false)
	private InsuranceProduct product;

	@Column(name = "version_no", nullable = false)
	private int versionNo;

	@Column(name = "sales_start_at", nullable = false)
	private LocalDateTime salesStartAt;

	@Column(name = "sales_end_at")
	private LocalDateTime salesEndAt;

	@Column(name = "terms_text", nullable = false, columnDefinition = "LONGTEXT")
	private String termsText;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "premium_terms", nullable = false, columnDefinition = "JSON")
	private String premiumTerms;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "eligible_work_groups_json", nullable = false, columnDefinition = "JSON")
	private String eligibleWorkGroups;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "station_selection_rule", nullable = false, columnDefinition = "JSON")
	private String stationSelectionRule;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "window_rule", nullable = false, columnDefinition = "JSON")
	private String windowRule;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "index_rules_json", nullable = false, columnDefinition = "JSON")
	private String indexRules;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "payout_rules_json", nullable = false, columnDefinition = "JSON")
	private String payoutRules;
}
