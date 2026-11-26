package com.wonday.farm.job.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.wonday.farm.testsupport.MySqlTestDatabase;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

// 기존 마이그레이션을 수정하지 않고, 격리 MySQL의 새 스키마에 V1~V9를 적용해 결과를 확인한다.
@Tag("integration")
class FlywayMigrationTests {

	@Test
	void emptyDatabaseAppliesV1ThroughV9() throws Exception {
		String schema = newSchema("mig_empty");
		MigrateResult result = flyway(schema, null).migrate();

		assertThat(result.success).isTrue();
		assertThat(result.migrationsExecuted).isEqualTo(9);
		assertThat(appliedVersions(schema)).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9");
		try (Connection connection = MySqlTestDatabase.rootConnection(schema)) {
			assertColumn(connection, schema, "job_posting", "time_zone", "varchar(64)", "YES", null);
			assertColumn(connection, schema, "work_schedule", "terms_version", "int", "NO", "1");
			assertColumn(connection, schema, "work_assignment", "agreed_work_date", "date", "YES", null);
			assertColumn(connection, schema, "work_assignment", "agreed_time_zone", "varchar(64)", "YES", null);
			assertColumn(connection, schema, "work_assignment", "confirmed_at_utc", "datetime(6)", "YES", null);
			assertIdentityColumns(connection, schema);
			assertEmptySalaryAccountTable(connection, schema);
		}
	}

	@Test
	void existingV3DataIsPreservedAndUpgradedByV9() throws Exception {
		String schema = newSchema("mig_upgrade");
		assertThat(flyway(schema, "3").migrate().success).isTrue();
		assertThat(appliedVersions(schema)).containsExactly("1", "2", "3");
		long legacyMemberId;
		try (Connection connection = MySqlTestDatabase.rootConnection(schema); Statement sql = connection.createStatement()) {
			// V3 시점 스키마에는 time_zone, terms_version이 없다.
			sql.executeUpdate("INSERT INTO member (login_id, password_hash, name, phone, role, resident_registration_number)"
				+ " VALUES ('legacy', 'h', '기존 고용주', '01000000000', 'EMPLOYER', '111111-1111111')");
			sql.executeUpdate("INSERT INTO job_posting (employer_id, title, description, crop_code, work_type_code, work_location,"
				+ " default_headcount, default_daily_wage) VALUES (LAST_INSERT_ID(), '기존 공고', 'd', 'C', 'W', 'L', 3, 100000)");
			sql.executeUpdate("INSERT INTO work_schedule (job_posting_id, work_date, planned_start_at, planned_end_at, status)"
				+ " VALUES (LAST_INSERT_ID(), '2026-01-05', '2026-01-05 08:00:00', '2026-01-05 17:00:00', 'OPEN')");
			try (ResultSet rs = sql.executeQuery("SELECT id FROM member WHERE login_id = 'legacy'")) {
				assertThat(rs.next()).isTrue();
				legacyMemberId = rs.getLong("id");
			}
		}

		MigrateResult result = flyway(schema, null).migrate();

		assertThat(result.success).isTrue();
		assertThat(result.migrationsExecuted).isEqualTo(6);
		assertThat(appliedVersions(schema)).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9");
		try (Connection connection = MySqlTestDatabase.rootConnection(schema); Statement sql = connection.createStatement()) {
			assertIdentityColumns(connection, schema);
			assertEmptySalaryAccountTable(connection, schema);
			// V8은 기존 번호를 이관하거나 국적을 추측하지 않고 새 네 컬럼을 NULL로 남긴다.
			try (ResultSet rs = sql.executeQuery("SELECT * FROM member WHERE login_id = 'legacy'")) {
				assertThat(rs.next()).isTrue();
				assertThat(rs.getLong("id")).isEqualTo(legacyMemberId);
				assertThat(rs.getString("password_hash")).isEqualTo("h");
				assertThat(rs.getString("name")).isEqualTo("기존 고용주");
				assertThat(rs.getString("phone")).isEqualTo("01000000000");
				assertThat(rs.getString("role")).isEqualTo("EMPLOYER");
				assertThat(rs.getString("resident_registration_number")).isEqualTo("111111-1111111");
				assertThat(rs.getString("nationality_type")).isNull();
				assertThat(rs.getString("identity_type")).isNull();
				assertThat(rs.getString("identity_ciphertext")).isNull();
				assertThat(rs.getString("identity_hmac")).isNull();
				assertThat(rs.next()).isFalse();
			}
			try (ResultSet rs = sql.executeQuery("SELECT title, time_zone, default_headcount FROM job_posting")) {
				assertThat(rs.next()).isTrue();
				assertThat(rs.getString("title")).isEqualTo("기존 공고");
				// 기존 공고의 시간대는 자동으로 채우지 않고 NULL로 남긴다 (보완은 후속 작업).
				assertThat(rs.getString("time_zone")).isNull();
				assertThat(rs.getInt("default_headcount")).isEqualTo(3);
				assertThat(rs.next()).isFalse();
			}
			try (ResultSet rs = sql.executeQuery("SELECT work_date, status, terms_version FROM work_schedule")) {
				assertThat(rs.next()).isTrue();
				assertThat(rs.getString("work_date")).isEqualTo("2026-01-05");
				assertThat(rs.getString("status")).isEqualTo("OPEN");
				assertThat(rs.getInt("terms_version")).isEqualTo(1);
				assertThat(rs.next()).isFalse();
			}
		}
	}

	@Test
	void identityStorageAllowsLegacyNullsAndCompleteDomesticAndForeignValues() throws Exception {
		String schema = migratedSchema("mig_identity_valid");
		try (Connection connection = MySqlTestDatabase.rootConnection(schema)) {
			// nullable UNIQUE는 미이관 회원의 여러 NULL을 허용해야 한다.
			insertIdentity(connection, "legacy-one", null, null, null, null);
			insertIdentity(connection, "legacy-two", null, null, null, null);
			insertIdentity(connection, "domestic", "DOMESTIC", "RESIDENT_REGISTRATION_NUMBER", "v1:synthetic-domestic", "a".repeat(64));
			insertIdentity(connection, "foreign", "FOREIGN", "FOREIGN_REGISTRATION_NUMBER", "v1:synthetic-foreign", "b".repeat(64));
			assertMemberCount(connection, 4);
			assertIdentityValues(connection, "legacy-one", null, null, null, null);
			assertIdentityValues(connection, "legacy-two", null, null, null, null);
			assertIdentityValues(connection, "domestic", "DOMESTIC", "RESIDENT_REGISTRATION_NUMBER", "v1:synthetic-domestic", "a".repeat(64));
			assertIdentityValues(connection, "foreign", "FOREIGN", "FOREIGN_REGISTRATION_NUMBER", "v1:synthetic-foreign", "b".repeat(64));
		}
	}

	@Test
	void identityCheckRejectsEveryPartiallyPopulatedCombination() throws Exception {
		String schema = migratedSchema("mig_identity_partial");
		try (Connection connection = MySqlTestDatabase.rootConnection(schema)) {
			// 네 값이 모두 NULL인 0과 모두 채워진 15를 제외한 14개 조합을 검사한다.
			for (int mask = 1; mask < 15; mask++) {
				int combination = mask;
				assertThatThrownBy(() -> insertIdentity(connection, "partial-" + combination,
					(combination & 1) == 0 ? null : "DOMESTIC",
					(combination & 2) == 0 ? null : "RESIDENT_REGISTRATION_NUMBER",
					(combination & 4) == 0 ? null : "v1:synthetic",
					(combination & 8) == 0 ? null : "a".repeat(64)))
					.as("일부 값만 채운 조합 %s", combination)
					.isInstanceOf(SQLException.class).hasMessageContaining("ck_member_identity_storage");
			}
			assertMemberCount(connection, 0);
		}
	}

	@Test
	void identityCheckRejectsMismatchedTypesAndInvalidStorageValues() throws Exception {
		String schema = migratedSchema("mig_identity_check");
		// 국적·종류의 대소문자와 조합, 빈 암호문·잘못된 HMAC 길이도 DB가 거절해야 한다.
		String[][] invalidValues = {
			{"DOMESTIC", "FOREIGN_REGISTRATION_NUMBER", "v1:synthetic", "a".repeat(64)},
			{"FOREIGN", "RESIDENT_REGISTRATION_NUMBER", "v1:synthetic", "a".repeat(64)},
			{"UNKNOWN", "RESIDENT_REGISTRATION_NUMBER", "v1:synthetic", "a".repeat(64)},
			{"DOMESTIC", "UNKNOWN", "v1:synthetic", "a".repeat(64)},
			{"domestic", "RESIDENT_REGISTRATION_NUMBER", "v1:synthetic", "a".repeat(64)},
			{"DOMESTIC", "resident_registration_number", "v1:synthetic", "a".repeat(64)},
			{"DOMESTIC", "RESIDENT_REGISTRATION_NUMBER", "", "a".repeat(64)},
			{"DOMESTIC", "RESIDENT_REGISTRATION_NUMBER", "v1:synthetic", "a".repeat(63)}
		};
		try (Connection connection = MySqlTestDatabase.rootConnection(schema)) {
			for (int index = 0; index < invalidValues.length; index++) {
				String loginId = "invalid-" + index;
				String[] values = invalidValues[index];
				assertThatThrownBy(() -> insertIdentity(connection, loginId, values[0], values[1], values[2], values[3]))
					.as("잘못된 저장값 %s", index)
					.isInstanceOf(SQLException.class).hasMessageContaining("ck_member_identity_storage");
			}
			assertMemberCount(connection, 0);
		}
	}

	@Test
	void identityHmacUniqueRejectsDuplicatesAcrossNationalityAndPreservesOriginal() throws Exception {
		String schema = migratedSchema("mig_identity_unique");
		try (Connection connection = MySqlTestDatabase.rootConnection(schema)) {
			insertIdentity(connection, "original", "DOMESTIC", "RESIDENT_REGISTRATION_NUMBER", "v1:first", "a".repeat(64));
			assertThatThrownBy(() -> insertIdentity(connection, "duplicate", "FOREIGN", "FOREIGN_REGISTRATION_NUMBER", "v1:second", "a".repeat(64)))
				.isInstanceOf(SQLException.class).hasMessageContaining("uq_member_identity_hmac");
			assertMemberCount(connection, 1);
			assertIdentityValues(connection, "original", "DOMESTIC", "RESIDENT_REGISTRATION_NUMBER", "v1:first", "a".repeat(64));
		}
	}

	@Test
	void identityCheckRejectsPartialUpdateAndPreservesStoredValues() throws Exception {
		String schema = migratedSchema("mig_identity_update");
		try (Connection connection = MySqlTestDatabase.rootConnection(schema); Statement sql = connection.createStatement()) {
			insertIdentity(connection, "original", "DOMESTIC", "RESIDENT_REGISTRATION_NUMBER", "v1:original", "a".repeat(64));
			assertThatThrownBy(() -> sql.executeUpdate("UPDATE member SET identity_hmac = NULL WHERE login_id = 'original'"))
				.isInstanceOf(SQLException.class).hasMessageContaining("ck_member_identity_storage");
			assertIdentityValues(connection, "original", "DOMESTIC", "RESIDENT_REGISTRATION_NUMBER", "v1:original", "a".repeat(64));
		}
	}

	private static String migratedSchema(String prefix) throws SQLException {
		String schema = newSchema(prefix);
		assertThat(flyway(schema, null).migrate().success).isTrue();
		return schema;
	}

	// 이 테스트는 암호화 알고리즘이 아니라 V8의 DB 제약을 검사하므로 가상 저장 문자열을 사용한다.
	private static void insertIdentity(Connection connection, String loginId, String nationality,
			String identityType, String ciphertext, String hmac) throws SQLException {
		try (var sql = connection.prepareStatement("INSERT INTO member"
				+ " (login_id, password_hash, name, role, nationality_type, identity_type, identity_ciphertext, identity_hmac)"
				+ " VALUES (?, 'synthetic-hash', '검증용 회원', 'WORKER', ?, ?, ?, ?)")) {
			sql.setString(1, loginId);
			sql.setString(2, nationality);
			sql.setString(3, identityType);
			sql.setString(4, ciphertext);
			sql.setString(5, hmac);
			assertThat(sql.executeUpdate()).isEqualTo(1);
		}
	}

	private static void assertMemberCount(Connection connection, int expected) throws SQLException {
		try (Statement sql = connection.createStatement(); ResultSet rs = sql.executeQuery("SELECT COUNT(*) FROM member")) {
			assertThat(rs.next()).isTrue();
			assertThat(rs.getInt(1)).isEqualTo(expected);
		}
	}

	private static void assertIdentityValues(Connection connection, String loginId, String nationality,
			String identityType, String ciphertext, String hmac) throws SQLException {
		try (var sql = connection.prepareStatement("SELECT nationality_type, identity_type, identity_ciphertext, identity_hmac"
				+ " FROM member WHERE login_id = ?")) {
			sql.setString(1, loginId);
			try (ResultSet rs = sql.executeQuery()) {
				assertThat(rs.next()).isTrue();
				assertThat(rs.getString("nationality_type")).isEqualTo(nationality);
				assertThat(rs.getString("identity_type")).isEqualTo(identityType);
				assertThat(rs.getString("identity_ciphertext")).isEqualTo(ciphertext);
				assertThat(rs.getString("identity_hmac")).isEqualTo(hmac);
				assertThat(rs.next()).isFalse();
			}
		}
	}

	private static void assertIdentityColumns(Connection connection, String schema) throws SQLException {
		assertColumn(connection, schema, "member", "nationality_type", "varchar(16)", "YES", null);
		assertColumn(connection, schema, "member", "identity_type", "varchar(32)", "YES", null);
		assertColumn(connection, schema, "member", "identity_ciphertext", "varchar(256)", "YES", null);
		assertColumn(connection, schema, "member", "identity_hmac", "varchar(64)", "YES", null);
		try (var sql = connection.prepareStatement("SELECT COLUMN_NAME, COLLATION_NAME FROM information_schema.COLUMNS"
				+ " WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'member' AND COLUMN_NAME IN ('identity_ciphertext', 'identity_hmac')")) {
			sql.setString(1, schema);
			try (ResultSet rs = sql.executeQuery()) {
				int count = 0;
				while (rs.next()) {
					assertThat(rs.getString("COLLATION_NAME")).as(rs.getString("COLUMN_NAME")).isEqualTo("ascii_bin");
					count++;
				}
				assertThat(count).isEqualTo(2);
			}
		}
	}

	private static void assertEmptySalaryAccountTable(Connection connection, String schema) throws SQLException {
		assertColumn(connection, schema, "member_salary_account", "member_id", "bigint", "NO", null);
		assertColumn(connection, schema, "member_salary_account", "bank_code", "char(3)", "NO", null);
		assertColumn(connection, schema, "member_salary_account", "account_number_ciphertext", "varchar(256)", "NO", null);
		assertColumn(connection, schema, "member_salary_account", "account_holder_name", "varchar(100)", "NO", null);
		assertColumn(connection, schema, "member_salary_account", "created_at", "datetime(6)", "NO", null);
		assertColumn(connection, schema, "member_salary_account", "updated_at", "datetime(6)", "NO", null);
		// 기존 회원에게 가상의 계좌를 자동 등록하지 않는다.
		try (Statement sql = connection.createStatement(); ResultSet rs = sql.executeQuery("SELECT COUNT(*) FROM member_salary_account")) {
			assertThat(rs.next()).isTrue();
			assertThat(rs.getLong(1)).isZero();
		}
	}

	private static String newSchema(String prefix) throws SQLException {
		String schema = prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		try (Connection connection = MySqlTestDatabase.rootConnection(""); Statement sql = connection.createStatement()) {
			sql.executeUpdate("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4");
		}
		return schema;
	}

	private static Flyway flyway(String schema, String target) {
		var config = Flyway.configure()
			.dataSource(MySqlTestDatabase.rootJdbcUrl(schema), "root", MySqlTestDatabase.rootPassword())
			.locations("classpath:db/migration");
		if (target != null) {
			config.target(target);
		}
		return config.load();
	}

	private static List<String> appliedVersions(String schema) {
		List<String> versions = new ArrayList<>();
		for (MigrationInfo info : flyway(schema, null).info().applied()) {
			assertThat(info.getState().isFailed()).isFalse();
			versions.add(info.getVersion().getVersion());
		}
		return versions;
	}

	private static void assertColumn(Connection connection, String schema, String table, String column,
			String type, String nullable, String defaultValue) throws SQLException {
		try (var statement = connection.prepareStatement("SELECT COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT"
				+ " FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND COLUMN_NAME = ?")) {
			statement.setString(1, schema);
			statement.setString(2, table);
			statement.setString(3, column);
			try (ResultSet rs = statement.executeQuery()) {
				assertThat(rs.next()).as(table + "." + column + " exists").isTrue();
				assertThat(rs.getString("COLUMN_TYPE")).isEqualTo(type);
				assertThat(rs.getString("IS_NULLABLE")).isEqualTo(nullable);
				assertThat(rs.getString("COLUMN_DEFAULT")).isEqualTo(defaultValue);
			}
		}
	}
}
