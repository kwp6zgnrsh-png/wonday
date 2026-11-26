package com.wonday.farm.member;

import java.util.Arrays;
import java.util.Optional;

import com.wonday.farm.member.identity.IdentityType;
import com.wonday.farm.member.identity.NationalityType;
import com.wonday.farm.member.identity.StoredIdentity;
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

	// 이관 전 기존 회원은 모두 null이다. 번호가 없다는 이유로 국적이나 번호를 추측하지 않는다.
	@Column(name = "nationality_type", length = 16)
	private String nationalityType;
	@Column(name = "identity_type", length = 32)
	private String identityType;
	@Column(name = "identity_ciphertext", length = 256)
	private String identityCiphertext;
	@Column(name = "identity_hmac", length = 64, unique = true)
	private String identityHmac;

	protected Member() {
	}

	public Member(String loginId, String passwordHash, String name, MemberRole role) {
		this.loginId = loginId;
		this.passwordHash = passwordHash;
		this.name = name;
		this.role = role.name();
	}

	// 새 가입 기능은 회원 ID를 발급받은 뒤 같은 트랜잭션 안에서 호출한다.
	// 평문을 회원 필드에 저장하지 않고 이미 있는 식별정보를 조용히 덮어쓰지 않는다.
	public void assignIdentity(StoredIdentity identity) {
		if (id == null || identity == null || id.longValue() != identity.memberId()) {
			throw new IllegalArgumentException("회원 식별정보 연결을 확인해 주세요.");
		}
		if (nationalityType != null || identityType != null || identityCiphertext != null || identityHmac != null) {
			throw new IllegalStateException("기존 식별정보 변경은 별도 이관 절차가 필요합니다.");
		}
		nationalityType = identity.nationalityType().name();
		identityType = identity.identityType().name();
		identityCiphertext = identity.ciphertext();
		identityHmac = identity.hmac();
	}

	public Optional<NationalityType> nationalityType() {
		return Arrays.stream(NationalityType.values()).filter(value -> value.name().equals(nationalityType)).findFirst();
	}

	public Optional<IdentityType> identityType() {
		return Arrays.stream(IdentityType.values()).filter(value -> value.name().equals(identityType)).findFirst();
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
