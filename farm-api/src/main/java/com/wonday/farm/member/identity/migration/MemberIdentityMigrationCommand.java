package com.wonday.farm.member.identity.migration;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import com.wonday.farm.member.identity.IdentityCrypto;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationState;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

// 웹 서버나 전체 Spring 컨텍스트를 띄우지 않고 이관 전용 JPA 팩토리만 구성한다.
public final class MemberIdentityMigrationCommand {
	private MemberIdentityMigrationCommand() {}

	public static void main(String[] args) {
		try {
			// 별도 JavaExec 프로세스에서 ORM의 원문 SQL 오류·바인딩 로그도 차단한다.
			silenceFrameworkLogs();
			run(args);
		} catch (MemberIdentityMigrationException issue) {
			System.err.println(issue.getMessage());
			System.exit(1);
		} catch (Exception exception) {
			System.err.println("식별정보 이관을 중단했습니다. 설정·키·DB 상태를 확인해 주세요. 원문 오류는 출력하지 않습니다.");
			System.exit(1);
		}
	}

	private static void run(String[] args) {
		Options options = parseOptions(args);
		IdentityCrypto crypto = new IdentityCrypto(env("FARM_IDENTITY_ENCRYPTION_KEY"), env("FARM_IDENTITY_HMAC_KEY"));
		crypto.requireConfigured();
		String jdbcUrl = env("FARM_IDENTITY_MIGRATION_JDBC_URL");
		if (!jdbcUrl.startsWith("jdbc:mysql://")) throw new MemberIdentityMigrationException("이관 대상 MySQL JDBC URL을 명시적으로 설정해 주세요.");
		String username = env("MYSQL_USER").isBlank() ? "wonday" : env("MYSQL_USER");
		String password = env("MYSQL_PASSWORD");
		if (password.isBlank()) throw new MemberIdentityMigrationException("이관용 DB 인증 설정이 필요합니다.");
		// JPA가 사용할 연결 설정이다. 직접 Connection·Statement를 사용하지 않는다.
		var dataSource = new DriverManagerDataSource(jdbcUrl, username, password);
		requireMigrations(dataSource);
		var factory = new LocalContainerEntityManagerFactoryBean();
		try {
			factory.setDataSource(dataSource);
			factory.setPersistenceUnitName("member-identity-migration");
			factory.setPackagesToScan("com.wonday.migration.identity");
			var adapter = new HibernateJpaVendorAdapter();
			adapter.setGenerateDdl(false);
			adapter.setShowSql(false);
			factory.setJpaVendorAdapter(adapter);
			factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate", "hibernate.show_sql", "false",
				"hibernate.format_sql", "false", "hibernate.generate_statistics", "false",
				// 명시적 격리 수준·읽기 전용 처리를 위해 트랜잭션 동안 연결을 유지한다.
				"hibernate.connection.handling_mode", "DELAYED_ACQUISITION_AND_HOLD"));
			factory.afterPropertiesSet();
			var entityManagerFactory = factory.getObject();
			if (entityManagerFactory == null) throw new MemberIdentityMigrationException("JPA 이관 설정을 확인해 주세요.");
			var transactions = new JpaTransactionManager(entityManagerFactory);
			transactions.setRollbackOnCommitFailure(true);
			var service = new MemberIdentityMigrationService(
				SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory), transactions, crypto);
			var result = service.migrate(options.ids(), options.apply());
			System.out.println((result.applied() ? "이관 완료" : "미리보기 완료 · DB 변경 없음")
				+ " / 선택 " + result.selected() + " / 신규 " + result.newlyMigrated() + " / 이미 이관됨 " + result.alreadyMigrated());
		} finally {
			factory.destroy();
		}
	}

	private static Options parseOptions(String[] args) {
		Set<Long> ids = new LinkedHashSet<>();
		boolean apply = false;
		boolean idsProvided = false;
		for (String argument : args) {
			if ("--apply".equals(argument)) {
				if (apply) throw new MemberIdentityMigrationException("중복 실행 옵션입니다.");
				apply = true;
			} else if (argument.startsWith("--confirmed-domestic-member-ids=")) {
				if (idsProvided) throw new MemberIdentityMigrationException("회원 목록 옵션은 한 번만 입력해 주세요.");
				idsProvided = true;
				String value = argument.substring("--confirmed-domestic-member-ids=".length());
				for (String item : value.split(",", -1)) {
					if (!item.matches("[1-9][0-9]{0,18}")) throw new MemberIdentityMigrationException("확인한 내국인 회원 ID 목록을 입력해 주세요.");
					long id;
					try { id = Long.parseLong(item); }
					catch (NumberFormatException exception) { throw new MemberIdentityMigrationException("회원 ID 범위를 확인해 주세요."); }
					if (!ids.add(id)) throw new MemberIdentityMigrationException("회원 ID가 중복되었습니다.");
				}
			} else {
				throw new MemberIdentityMigrationException("허용되지 않은 옵션입니다. 확인된 내국인 회원 ID와 선택적 --apply만 사용합니다.");
			}
		}
		if (ids.isEmpty()) throw new MemberIdentityMigrationException("직접 확인한 내국인 회원 ID 목록이 필요합니다.");
		return new Options(Collections.unmodifiableSet(ids), apply);
	}

	private static void requireMigrations(DriverManagerDataSource dataSource) {
		// Flyway의 이력 조회만 수행한다. migrate·repair·clean은 호출하지 않는다.
		var applied = Arrays.asList(Flyway.configure().dataSource(dataSource).load().info().applied());
		for (var required : Map.of("7", "V7__add_work_location_coordinates.sql", "8", "V8__add_member_identity_storage.sql").entrySet()) {
			boolean found = applied.stream().anyMatch(info -> info.getVersion() != null
				&& required.getKey().equals(info.getVersion().getVersion()) && required.getValue().equals(info.getScript())
				&& info.getState() == MigrationState.SUCCESS);
			if (!found) throw new MemberIdentityMigrationException("선행 좌표 V7과 식별정보 V8의 적용 상태를 먼저 확인해 주세요.");
		}
	}

	private static void silenceFrameworkLogs() {
		if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) {
			throw new MemberIdentityMigrationException("이관 전용 로그 설정을 확인해 주세요.");
		}
		context.reset();
		context.getLogger(Logger.ROOT_LOGGER_NAME).setLevel(Level.OFF);
	}

	private static String env(String name) {
		String value = System.getenv(name);
		return value == null ? "" : value;
	}

	private record Options(Set<Long> ids, boolean apply) {}
}
