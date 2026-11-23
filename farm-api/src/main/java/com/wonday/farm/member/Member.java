package com.wonday.farm.member;

import java.util.Arrays;
import java.util.Optional;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// 농업 플랫폼에서 사전에 등록한 하나의 계정을 기존 member 테이블에 매핑한다.
@Entity
@Table(name = "member")
public class Member {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "login_id", nullable = false, unique = true, length = 100)
	private String loginId;

	@Column(name = "password_hash", nullable = false, length = 255)
	private String passwordHash;

	@Column(nullable = false, length = 100)
	private String name;

	@Column(length = 32)
	private String phone;

	@Column(nullable = false, length = 32)
	private String role;

	protected Member() {
	}

	public Member(String loginId, String passwordHash, String name, MemberRole role) {
		this.loginId = loginId;
		this.passwordHash = passwordHash;
		this.name = name;
		this.role = role.name();
	}

	public Long getId() {
		return id;
	}

	public String getLoginId() {
		return loginId;
	}

	public String passwordHash() {
		return passwordHash;
	}

	public String displayName() {
		return name;
	}

	public Optional<MemberRole> memberRole() {
		// 기존 DB의 값도 읽을 수 있게 문자열로 매핑하고, 인증할 때 허용된 역할 코드인지 확인한다.
		return Arrays.stream(MemberRole.values())
			.filter(candidate -> candidate.name().equals(role))
			.findFirst();
	}
}
