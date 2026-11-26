package com.wonday.farm.job.migration;

import static org.assertj.core.api.Assertions.assertThat;

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

// 기존 마이그레이션을 수정하지 않고, 격리 MySQL의 새 스키마에 V1~V7을 적용해 결과를 확인한다.
@Tag("integration")
class FlywayMigrationTests {

	@Test
	void emptyDatabaseAppliesV1ThroughV7() throws Exception {
		String schema = newSchema("mig_empty");
		MigrateResult result = flyway(schema, null).migrate();

		assertThat(result.success).isTrue();
		assertThat(appliedVersions(schema)).containsExactly("1", "2", "3", "4", "5", "6", "7");
		try (Connection connection = MySqlTestDatabase.rootConnection(schema)) {
			assertColumn(connection, schema, "job_posting", "time_zone", "varchar(64)", "YES", null);
			assertColumn(connection, schema, "work_schedule", "terms_version", "int", "NO", "1");
			assertColumn(connection, schema, "work_assignment", "agreed_work_date", "date", "YES", null);
			assertColumn(connection, schema, "work_assignment", "agreed_time_zone", "varchar(64)", "YES", null);
			assertColumn(connection, schema, "work_assignment", "confirmed_at_utc", "datetime(6)", "YES", null);
		}
	}

	@Test
	void existingV3DataIsPreservedAndUpgradedByV7() throws Exception {
		String schema = newSchema("mig_upgrade");
		assertThat(flyway(schema, "3").migrate().success).isTrue();
		assertThat(appliedVersions(schema)).containsExactly("1", "2", "3");
		try (Connection connection = MySqlTestDatabase.rootConnection(schema); Statement sql = connection.createStatement()) {
			// V3 시점 스키마에는 time_zone, terms_version이 없다.
			sql.executeUpdate("INSERT INTO member (login_id, password_hash, name, role) VALUES ('legacy', 'h', '기존 고용주', 'EMPLOYER')");
			sql.executeUpdate("INSERT INTO job_posting (employer_id, title, description, crop_code, work_type_code, work_location,"
				+ " default_headcount, default_daily_wage) VALUES (LAST_INSERT_ID(), '기존 공고', 'd', 'C', 'W', 'L', 3, 100000)");
			sql.executeUpdate("INSERT INTO work_schedule (job_posting_id, work_date, planned_start_at, planned_end_at, status)"
				+ " VALUES (LAST_INSERT_ID(), '2026-01-05', '2026-01-05 08:00:00', '2026-01-05 17:00:00', 'OPEN')");
		}

		MigrateResult result = flyway(schema, null).migrate();

		assertThat(result.success).isTrue();
		assertThat(result.migrationsExecuted).isEqualTo(3);
		assertThat(appliedVersions(schema)).containsExactly("1", "2", "3", "4", "5", "6", "7");
		try (Connection connection = MySqlTestDatabase.rootConnection(schema); Statement sql = connection.createStatement()) {
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
