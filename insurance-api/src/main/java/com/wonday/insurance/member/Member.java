package com.wonday.insurance.member;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "member")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class Member {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "login_id", nullable = false, length = 100)
	private String loginId;

	@Column(name = "password_hash", nullable = false)
	private String passwordHash;

	@Column(nullable = false, length = 100)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private MemberRole role;

	@Column(length = 32)
	private String phone;

	@Enumerated(EnumType.STRING)
	@Column(name = "nationality_type", length = 16)
	private NationalityType nationalityType;

	@Enumerated(EnumType.STRING)
	@Column(name = "identity_type", length = 32)
	private IdentityType identityType;

	@Column(name = "identity_number_ciphertext", columnDefinition = "TEXT")
	private String identityNumberCiphertext;

	void updateEnrollmentContact(String phone, NationalityType nationalityType, String ciphertext) {
		this.phone = phone;
		this.nationalityType = nationalityType;
		this.identityType = IdentityType.forNationality(nationalityType);
		this.identityNumberCiphertext = ciphertext;
	}

	Member(String loginId, String passwordHash, String name, MemberRole role) {
		this(loginId, passwordHash, name, role, NationalityType.DOMESTIC);
	}

	Member(String loginId, String passwordHash, String name, MemberRole role, NationalityType nationalityType) {
		this.loginId = loginId;
		this.passwordHash = passwordHash;
		this.name = name;
		this.role = role;
		this.nationalityType = nationalityType;
	}
}
