package com.wonday.insurance.member.migration;

import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
public class IdentityMigrationConfiguration {
	@Bean
	V8__encrypt_legacy_member_identity legacyIdentityMigrationHistory() {
		return new V8__encrypt_legacy_member_identity();
	}

	@Bean
	@DependsOnDatabaseInitialization
	InitializingBean legacyIdentityStartupCheck(DataSource dataSource) {
		// 평문 값을 읽거나 바꾸지 않는다. SQL 마이그레이션 완료 뒤, 서버·스케줄러 시작 전에 검사한다.
		return () -> requireCompleted(new JdbcTemplate(dataSource));
	}

	public static void requireCompleted(JdbcTemplate jdbc) {
		Integer remaining = jdbc.queryForObject(
			"select count(*) from member where resident_registration_number is not null", Integer.class);
		if (remaining != null && remaining > 0) {
			throw new IllegalStateException("Legacy member identity migration is required; run migrateMemberIdentity explicitly before starting insurance-api");
		}
	}
}
