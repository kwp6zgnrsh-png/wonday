package com.wonday.migration.insurance.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 일반 보험 앱의 엔티티 스캔 밖에 둔다. 이관 전용 JPA 팩토리만 평문 컬럼을 읽고 제거한다. */
@Entity(name = "IdentityMigrationMember")
@Table(name = "member")
public class IdentityMigrationMember {
	@Id private Long id;
	@Column(name = "resident_registration_number", length = 32) private String legacyNumber;
	@Column(name = "nationality_type", length = 16) private String nationality;
	@Column(name = "identity_type", length = 32) private String type;
	@Column(name = "identity_number_ciphertext", columnDefinition = "TEXT") private String ciphertext;

	protected IdentityMigrationMember() {}
	public long id() { return id; }
	public String legacy() { return legacyNumber; }
	public String nationality() { return nationality; }
	public String type() { return type; }
	public String ciphertext() { return ciphertext; }

	public void migrateDomestic(String encrypted) {
		nationality = "DOMESTIC";
		type = "RESIDENT_REGISTRATION_NUMBER";
		ciphertext = encrypted;
		legacyNumber = null;
	}

	@Override public String toString() { return "IdentityMigrationMember[redacted]"; }
}
