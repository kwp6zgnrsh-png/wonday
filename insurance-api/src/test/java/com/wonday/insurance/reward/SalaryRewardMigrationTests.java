package com.wonday.insurance.reward;

import static org.assertj.core.api.Assertions.assertThat;

import com.wonday.insurance.member.migration.V8__encrypt_legacy_member_identity;
import com.zaxxer.hikari.HikariDataSource;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@SpringBootTest
class SalaryRewardMigrationTests {

	@Autowired DataSource dataSource;

	@Test
	void upgradingFromV8PreservesExistingMembersAndLinks() throws Exception {
		// 임시 테스트 MySQL에만 임의 스키마를 만든다. 기존 개발 스키마는 사용하지 않는다.
		String schema = "insurance_reward_migration_" + UUID.randomUUID().toString().replace("-", "");
		HikariDataSource source = dataSource.unwrap(HikariDataSource.class);
		String url = source.getJdbcUrl().replaceFirst("/[^/?]+(?=\\?|$)", "/" + schema);
		DataSource database = new DriverManagerDataSource(url, source.getUsername(), source.getPassword());
		try {
			migration(database, "8").migrate();
			JdbcTemplate jdbc = new JdbcTemplate(database);
			jdbc.update("insert into member (id,login_id,password_hash,name,role) values (11,'existing','hash','Worker','WORKER')");
			jdbc.update("""
				insert into platform_link (id,member_id,platform_worker_id,consent_version,consented_at)
				values (21,11,123,'farm-platform-v1','2026-11-20 00:00:00.123456')
				""");
			var member = jdbc.queryForMap("select * from member where id = 11");
			var link = jdbc.queryForMap("select * from platform_link where id = 21");
			Flyway next = migration(database, "9");
			assertThat(next.migrate().migrationsExecuted).isEqualTo(1);
			assertThat(jdbc.queryForMap("select * from member where id = 11")).isEqualTo(member);
			assertThat(jdbc.queryForMap("select * from platform_link where id = 21")).isEqualTo(link);
			assertThat(jdbc.queryForObject("select count(*) from salary_reward_check", Integer.class)).isZero();
			assertThat(next.migrate().migrationsExecuted).isZero();
		} finally {
			try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
				statement.execute("drop database if exists `" + schema + "`");
			}
		}
	}

	private Flyway migration(DataSource database, String target) {
		return Flyway.configure().dataSource(database).locations("classpath:db/migration")
			.javaMigrations(new V8__encrypt_legacy_member_identity()).target(target).load();
	}
}
