package com.wonday.insurance.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wonday.insurance.member.migration.V8__encrypt_legacy_member_identity;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@SpringBootTest
class MemberIdentityMigrationTests {

	@Autowired DataSource dataSource;
	@Autowired IdentityCipher cipher;

	@Test
	void legacyIdentityIsEncryptedAndUnclassifiedMembersAreNotGuessed() throws Exception {
		withLegacyDatabase(database -> {
			JdbcTemplate jdbc = new JdbcTemplate(database);
			jdbc.update("""
				insert into member (id, login_id, password_hash, name, role, phone, resident_registration_number)
				values (11, 'legacy-worker', 'unchanged-password-hash', 'Existing worker', 'WORKER', '01000000000', '000000-0000000'),
				       (12, 'legacy-admin', 'unchanged-admin-hash', 'Existing admin', 'ADMIN', null, null)
				""");
			Flyway flyway = migrate(database, "8");
			var worker = jdbc.queryForMap("select * from member where id = 11");
			assertThat(worker.get("nationality_type")).isEqualTo("DOMESTIC");
			assertThat(worker.get("identity_type")).isEqualTo("RESIDENT_REGISTRATION_NUMBER");
			String ciphertext = (String) worker.get("identity_number_ciphertext");
			assertThat(ciphertext).startsWith("v1:").doesNotContain("0000000000000");
			assertThat(cipher.decrypt(11, IdentityType.RESIDENT_REGISTRATION_NUMBER, ciphertext)).isEqualTo("0000000000000");
			assertThat(worker.get("resident_registration_number")).isNull();
			assertThat(worker.get("password_hash")).isEqualTo("unchanged-password-hash");
			assertThat(worker.get("phone")).isEqualTo("01000000000");
			var admin = jdbc.queryForMap("select * from member where id = 12");
			assertThat(admin.get("nationality_type")).isNull();
			assertThat(admin.get("identity_type")).isNull();
			assertThat(admin.get("identity_number_ciphertext")).isNull();
			assertThat(admin.get("role")).isEqualTo("ADMIN");
			assertThat(flyway.migrate().migrationsExecuted).isZero();
			assertThat(jdbc.queryForObject("select identity_number_ciphertext from member where id = 11", String.class))
				.isEqualTo(ciphertext);
		});
	}

	@Test
	void malformedLegacyIdentityStopsMigrationBeforeAnyIdentityIsChanged() throws Exception {
		withLegacyDatabase(database -> {
			JdbcTemplate jdbc = new JdbcTemplate(database);
			jdbc.update("""
				insert into member (login_id, password_hash, name, role, resident_registration_number)
				values ('valid', 'hash', 'Valid', 'WORKER', '0000000000000'),
				       ('invalid', 'hash', 'Invalid', 'WORKER', 'needs-correction')
				""");
			assertThatThrownBy(() -> migrate(database, "8"))
				.hasRootCauseMessage("Legacy member identity requires explicit data correction");
			assertThat(jdbc.queryForObject("select count(*) from member where resident_registration_number is not null", Integer.class))
				.isEqualTo(2);
			assertThat(jdbc.queryForObject("select count(*) from member where identity_number_ciphertext is not null", Integer.class))
				.isZero();
		});
	}

	@Test
	void databaseRejectsMismatchedIdentityTypesAndMixedPlaintextStorage() throws Exception {
		withLegacyDatabase(database -> {
			migrate(database, "7");
			JdbcTemplate jdbc = new JdbcTemplate(database);
			jdbc.update("insert into member (id, login_id, password_hash, name, role) values (1, 'test', 'hash', 'Test', 'WORKER')");
			assertCheckRejected(jdbc, """
				update member set nationality_type = 'FOREIGN', identity_type = 'RESIDENT_REGISTRATION_NUMBER',
				identity_number_ciphertext = 'test' where id = 1
				""");
			assertCheckRejected(jdbc, """
				update member set nationality_type = 'DOMESTIC', identity_type = 'RESIDENT_REGISTRATION_NUMBER',
				identity_number_ciphertext = 'test', resident_registration_number = '0000000000000' where id = 1
				""");
			assertCheckRejected(jdbc, "update member set identity_number_ciphertext = 'test' where id = 1");
			assertCheckRejected(jdbc, "update member set nationality_type = 'UNKNOWN' where id = 1");
		});
	}

	private static void assertCheckRejected(JdbcTemplate jdbc, String sql) {
		// JDBC 예외 번역 방식 대신 실제 MySQL CHECK 위반(3819)인지 확인한다.
		assertThatThrownBy(() -> jdbc.update(sql)).rootCause().isInstanceOfSatisfying(SQLException.class,
			exception -> assertThat(exception.getErrorCode()).isEqualTo(3819));
	}

	private Flyway migrate(DataSource database, String target) {
		Flyway flyway = Flyway.configure().dataSource(database).locations("classpath:db/migration")
			.javaMigrations(new V8__encrypt_legacy_member_identity(cipher)).target(target).load();
		flyway.migrate();
		return flyway;
	}

	private void withLegacyDatabase(MigrationCheck check) throws Exception {
		// 앱·개발 스키마와 분리한 임의 이름만 만들고 제거한다. 테스트 DB 계정에만 생성 권한이 필요하다.
		String schema = "insurance_migration_" + UUID.randomUUID().toString().replace("-", "");
		HikariDataSource source = dataSource.unwrap(HikariDataSource.class);
		String url = source.getJdbcUrl().replaceFirst("/[^/?]+(?=\\?|$)", "/" + schema);
		DataSource database = new DriverManagerDataSource(url, source.getUsername(), source.getPassword());
		try {
			migrate(database, "6");
			check.run(database);
		} finally {
			try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
				statement.execute("drop database if exists `" + schema + "`");
			}
		}
	}

	@FunctionalInterface
	private interface MigrationCheck {
		void run(DataSource database) throws Exception;
	}
}
