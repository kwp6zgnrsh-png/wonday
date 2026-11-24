package com.wonday.insurance.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "insurance_product")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class InsuranceProduct {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "product_code", nullable = false, length = 64)
	private String productCode;

	@Column(nullable = false, length = 150)
	private String name;

	@Column(nullable = false, columnDefinition = "TEXT")
	private String description;

	@Column(nullable = false, length = 32)
	private String status;
}
