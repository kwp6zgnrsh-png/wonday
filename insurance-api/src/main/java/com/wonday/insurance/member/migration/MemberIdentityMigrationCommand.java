package com.wonday.insurance.member.migration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import com.wonday.insurance.member.IdentityCipher;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationState;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

/** 웹 서버·전체 Spring 컨텍스트 없이 이관 전용 JPA 팩토리만 시작하는 독립 명령이다. */
public final class MemberIdentityMigrationCommand {
	private MemberIdentityMigrationCommand() {}

	public static void main(String[] args) {
		// Windows에서도 미리보기·오류 결과를 파일로 보관할 수 있도록 명령 출력 인코딩을 고정한다.
		System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
		System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));
		try {
			// 별도 프로세스에서만 적용한다. JDBC·ORM 오류에 번호/암호문/접속 정보가 섞이지 않게 한다.
			silenceFrameworkLogs();
			var options = parseOptions(args);
			var cipher = new IdentityCipher(env("INSURANCE_IDENTITY_ENCRYPTION_KEY"));
			String url = env("INSURANCE_IDENTITY_MIGRATION_JDBC_URL");
			if (!url.startsWith("jdbc:mysql://")) throw new MemberIdentityMigrationException("이관 대상 MySQL JDBC URL을 명시적으로 설정해 주세요.");
			String username = env("MYSQL_USER").isBlank() ? "wonday" : env("MYSQL_USER");
			String password = env("MYSQL_PASSWORD");
			if (password.isBlank()) throw new MemberIdentityMigrationException("이관용 DB 인증 설정이 필요합니다.");
			var database = new DriverManagerDataSource(url, username, password);
			requireSchema(database);
			var factory = entityManagerFactory(database);
			try {
				var manager = factory.getObject();
				if (manager == null) throw new MemberIdentityMigrationException("JPA 이관 설정을 확인해 주세요.");
				var transactions = new JpaTransactionManager(manager);
				transactions.setRollbackOnCommitFailure(true);
				var service = new MemberIdentityMigrationService(SharedEntityManagerCreator.createSharedEntityManager(manager), transactions, cipher);
				var result = service.migrate(options.ids(), options.apply());
				System.out.println((result.applied() ? "이관 완료" : "미리보기 완료 · DB 변경 없음")
					+ " / 선택 " + result.selected() + " / 신규 " + result.newlyMigrated() + " / 이미 이관됨 " + result.alreadyMigrated());
			} finally {
				factory.destroy();
			}
		} catch (MemberIdentityMigrationException exception) {
			System.err.println(exception.getMessage());
			System.exit(1);
		} catch (Exception exception) {
			System.err.println("식별정보 이관을 중단했습니다. 설정·키·DB 상태를 확인해 주세요. 원문 오류는 출력하지 않습니다.");
			System.exit(1);
		}
	}

	// 테스트와 명령이 동일한 전용 엔티티·트랜잭션 구성을 사용한다. 일반 Member 엔티티는 스캔하지 않는다.
	static LocalContainerEntityManagerFactoryBean entityManagerFactory(DriverManagerDataSource database) {
		var factory = new LocalContainerEntityManagerFactoryBean();
		factory.setDataSource(database);
		factory.setPersistenceUnitName("insurance-member-identity-migration");
		factory.setPackagesToScan("com.wonday.migration.insurance.identity");
		var adapter = new HibernateJpaVendorAdapter();
		adapter.setGenerateDdl(false);
		adapter.setShowSql(false);
		factory.setJpaVendorAdapter(adapter);
		factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate", "hibernate.show_sql", "false",
			"hibernate.format_sql", "false", "hibernate.generate_statistics", "false",
			"hibernate.connection.handling_mode", "DELAYED_ACQUISITION_AND_HOLD"));
		factory.afterPropertiesSet();
		return factory;
	}

	static Options parseOptions(String[] args) {
		Set<Long> ids = new LinkedHashSet<>();
		boolean apply = false, provided = false;
		for (String argument : args) {
			if (argument.equals("--apply") && !apply) {
				apply = true;
			} else if (argument.startsWith("--confirmed-domestic-member-ids=") && !provided) {
				provided = true;
				for (String value : argument.substring("--confirmed-domestic-member-ids=".length()).split(",", -1)) {
					if (!value.matches("[1-9][0-9]{0,18}")) throw invalidOptions();
					long id;
					try { id = Long.parseLong(value); } catch (NumberFormatException exception) { throw invalidOptions(); }
					if (!ids.add(id)) throw invalidOptions();
				}
			} else {
				throw invalidOptions();
			}
		}
		if (ids.isEmpty()) throw invalidOptions();
		return new Options(Collections.unmodifiableSet(ids), apply);
	}

	private static MemberIdentityMigrationException invalidOptions() {
		return new MemberIdentityMigrationException("확인한 내국인 회원 ID 목록과 선택적 --apply만 지정해 주세요. 중복·잘못된 옵션은 허용하지 않습니다.");
	}

	static void requireSchema(DriverManagerDataSource database) {
		// 읽기만 한다. migrate·repair·clean이나 Flyway 이력 수정은 이 명령에서 실행하지 않는다.
		boolean ready = Arrays.stream(Flyway.configure().dataSource(database)
			.javaMigrations(new V8__encrypt_legacy_member_identity()).load().info().applied())
			.anyMatch(info -> info.getVersion() != null && info.getVersion().getVersion().equals("7")
				&& info.getScript().equals("V7__add_member_nationality_and_encrypted_identity.sql")
				&& info.getState() == MigrationState.SUCCESS);
		if (!ready) throw new MemberIdentityMigrationException("보험 V7 스키마의 정상 적용 상태를 먼저 확인해 주세요.");
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

	record Options(Set<Long> ids, boolean apply) {}
}
