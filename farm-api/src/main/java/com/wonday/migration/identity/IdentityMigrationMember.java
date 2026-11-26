package com.wonday.migration.identity;

import com.wonday.farm.member.identity.StoredIdentity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// 일반 농업 앱의 엔티티 스캔(com.wonday.farm) 밖에 둔 이관 전용 매핑이다.
// 이관용 팩토리만 읽으며 일반 로그인 조회에 기존 번호 원문을 추가하지 않는다.
@Entity(name = "IdentityMigrationMember")
@Table(name = "member")
public class IdentityMigrationMember {
	@Id
	private Long id;

	@Column(name = "resident_registration_number", length = 32, insertable = false, updatable = false)
	private String legacyNumber;
	@Column(name = "nationality_type", length = 16)
	private String nationality;
	@Column(name = "identity_type", length = 32)
	private String type;
	@Column(name = "identity_ciphertext", length = 256)
	private String ciphertext;
	@Column(name = "identity_hmac", length = 64)
	private String hmac;

	protected IdentityMigrationMember() {}

	public long id() { return id; }
	public String legacy() { return legacyNumber; }
	public String nationality() { return nationality; }
	public String type() { return type; }
	public String ciphertext() { return ciphertext; }
	public String hmac() { return hmac; }
	public boolean hasAnyStored() { return nationality != null || type != null || ciphertext != null || hmac != null; }

	public void assignIdentity(StoredIdentity identity) {
		if (identity == null || id == null || id.longValue() != identity.memberId()) {
			throw new IllegalArgumentException("회원 식별정보 연결을 확인해 주세요.");
		}
		if (hasAnyStored()) throw new IllegalStateException("기존 식별정보를 덮어쓸 수 없습니다.");
		nationality = identity.nationalityType().name();
		type = identity.identityType().name();
		ciphertext = identity.ciphertext();
		hmac = identity.hmac();
	}

	@Override public String toString() { return "IdentityMigrationMember[redacted]"; }
}