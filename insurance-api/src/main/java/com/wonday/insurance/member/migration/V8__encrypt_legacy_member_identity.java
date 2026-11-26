package com.wonday.insurance.member.migration;

import com.wonday.insurance.member.IdentityCipher;
import com.wonday.insurance.member.IdentityType;
import java.sql.Connection;
import java.util.ArrayList;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.stereotype.Component;

// Spring Boot가 JavaMigration 빈을 Flyway에 등록한다. 암호화 키는 SQL 파일이나 이력 테이블에 남기지 않는다.
@Component
public final class V8__encrypt_legacy_member_identity extends BaseJavaMigration {

	private final IdentityCipher cipher;

	public V8__encrypt_legacy_member_identity(IdentityCipher cipher) {
		this.cipher = cipher;
	}

	@Override
	public Integer getChecksum() {
		return 1;
	}

	@Override
	public void migrate(Context context) throws Exception {
		Connection connection = context.getConnection();
		// 먼저 모든 기존 값의 형식을 검사한다. 임의 번호로 보완하거나 일부 행만 이관한 채 진행하지 않는다.
		try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
			select id from member where resident_registration_number is not null
			and not regexp_like(regexp_replace(resident_registration_number, '[-[:space:]]', ''), '^[0-9]{13}$', 'c')
			limit 1
			""")) {
			if (rows.next()) throw new IllegalStateException("Legacy member identity requires explicit data correction");
		}
		long afterId = 0;
		while (true) {
			var batch = new ArrayList<LegacyIdentity>();
			try (var select = connection.prepareStatement("""
				select id, regexp_replace(resident_registration_number, '[-[:space:]]', '') as identity_number
				from member where id > ? and resident_registration_number is not null order by id limit 100
				""")) {
				select.setLong(1, afterId);
				try (var rows = select.executeQuery()) {
					while (rows.next()) batch.add(new LegacyIdentity(rows.getLong(1), rows.getString(2)));
				}
			}
			if (batch.isEmpty()) return;
			try (var update = connection.prepareStatement("""
				update member set nationality_type = 'DOMESTIC', identity_type = 'RESIDENT_REGISTRATION_NUMBER',
				identity_number_ciphertext = ?, resident_registration_number = null where id = ?
				""")) {
				for (LegacyIdentity identity : batch) {
					update.setString(1, cipher.encrypt(identity.memberId(), IdentityType.RESIDENT_REGISTRATION_NUMBER, identity.number()));
					update.setLong(2, identity.memberId());
					update.addBatch();
					afterId = identity.memberId();
				}
				update.executeBatch();
			}
		}
	}

	private record LegacyIdentity(long memberId, String number) {
		@Override public String toString() { return "LegacyIdentity[redacted]"; }
	}
}
