package com.wonday.insurance.member.migration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.wonday.insurance.member.IdentityCipher;
import com.wonday.insurance.member.IdentityType;
import com.wonday.migration.insurance.identity.IdentityMigrationMember;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.AdditionalAnswers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;

@SpringBootTest
class MemberIdentityMigrationServiceTests {
	private static final String NUMBER="0000000000000", PUBLIC_TEST_KEY="AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
	@Autowired DataSource dataSource;
	@Autowired IdentityCipher cipher;
	@TempDir Path processDirectory;

	@Test void previewDoesNotWriteAndApplyPreservesOtherDataAndIsIdempotent() throws Exception {
		withDatabase(s -> {
			legacy(s.jdbc(),11,"000000-0000000"); legacy(s.jdbc(),12,NUMBER);
			var before=members(s.jdbc()); var history=history(s.jdbc());
			assertThat(s.service().migrate(Set.of(11L),false)).isEqualTo(new MemberIdentityMigrationService.Result(1,1,0,false));
			assertThat(members(s.jdbc())).isEqualTo(before);
			assertThat(history(s.jdbc())).isEqualTo(history);
			assertThat(s.service().migrate(Set.of(11L),true)).isEqualTo(new MemberIdentityMigrationService.Result(1,1,0,true));
			var row=s.jdbc().queryForMap("select * from member where id=11");
			String encrypted=(String)row.get("identity_number_ciphertext");
			assertThat(encrypted).startsWith("v1:").doesNotContain(NUMBER);
			assertThat(cipher.decrypt(11,IdentityType.RESIDENT_REGISTRATION_NUMBER,encrypted)).isEqualTo(NUMBER);
			assertThat(row.get("resident_registration_number")).isNull();
			assertThat(row.get("nationality_type")).isEqualTo("DOMESTIC");
			assertThat(row.get("identity_type")).isEqualTo("RESIDENT_REGISTRATION_NUMBER");
			for (String field:Set.of("login_id","password_hash","name","role","phone")) assertThat(row.get(field)).isEqualTo(before.getFirst().get(field));
			assertThat(members(s.jdbc()).getLast()).isEqualTo(before.getLast());
			assertThat(s.service().migrate(Set.of(11L),true)).isEqualTo(new MemberIdentityMigrationService.Result(1,0,1,true));
			assertThat(s.jdbc().queryForMap("select * from member where id=11")).isEqualTo(row);
			assertThat(history(s.jdbc())).isEqualTo(history);
		});
	}

	@ParameterizedTest
	@ValueSource(strings={"malformed","missing","unknown-id","foreign","wrong-key","tampered"})
	void invalidSelectionDoesNotChangeAnySelectedMember(String kind) throws Exception {
		withDatabase(s -> {
			legacy(s.jdbc(),11,NUMBER);
			if (!kind.equals("unknown-id")) legacy(s.jdbc(),12,kind.equals("malformed")?"needs-correction":null);
			if (Set.of("foreign","wrong-key","tampered").contains(kind)) {
				boolean foreign=kind.equals("foreign");
				IdentityType type=foreign?IdentityType.FOREIGN_REGISTRATION_NUMBER:IdentityType.RESIDENT_REGISTRATION_NUMBER;
				var otherKey=new IdentityCipher("Hh0cGxoZGBcWFRQTEhEQDw4NDAsKCQgHBgUEAwIBAAA=");
				String encrypted=kind.equals("tampered")?"v1:invalid":(foreign?cipher:otherKey).encrypt(12,type,NUMBER);
				s.jdbc().update("update member set nationality_type=?,identity_type=?,identity_number_ciphertext=? where id=12",foreign?"FOREIGN":"DOMESTIC",type.name(),encrypted);
			}
			var before=members(s.jdbc()); var history=history(s.jdbc());
			assertThatThrownBy(() -> s.service().migrate(Set.of(11L,12L),true)).isInstanceOf(MemberIdentityMigrationException.class)
				.hasMessageNotContaining(NUMBER).hasMessageNotContaining("needs-correction");
			assertThat(members(s.jdbc())).isEqualTo(before);
			assertThat(history(s.jdbc())).isEqualTo(history);
		});
	}

	@Test void databaseFailureRollsBackCiphertextAndPlaintextRemovalTogether() throws Exception {
		withDatabase(s -> {
			legacy(s.jdbc(),11,NUMBER); legacy(s.jdbc(),12,NUMBER);
			var before=members(s.jdbc());
			s.jdbc().execute("create trigger fail_second_migration before update on member for each row begin if new.id=12 then signal sqlstate '45000' set message_text='Synthetic write failure'; end if; end");
			assertThatThrownBy(() -> s.service().migrate(Set.of(11L,12L),true)).isInstanceOf(RuntimeException.class);
			assertThat(members(s.jdbc())).isEqualTo(before);
		});
	}

	@Test void overlappingCommandsSerializeOnMemberAndReuseTheFirstCiphertext() throws Exception {
		withDatabase(s -> {
			legacy(s.jdbc(),11,NUMBER);
			CountDownLatch locked=new CountDownLatch(1),secondAtQuery=new CountDownLatch(1),release=new CountDownLatch(1);
			IdentityCipher blockingCipher=spy(cipher);
			doAnswer(invocation -> {
				locked.countDown(); assertThat(release.await(15,TimeUnit.SECONDS)).isTrue();
				return invocation.callRealMethod();
			}).when(blockingCipher).encrypt(eq(11L),any(),anyString());
			EntityManager secondEntities=mock(EntityManager.class,AdditionalAnswers.delegatesTo(s.entities()));
			doAnswer(invocation -> {
				TypedQuery<IdentityMigrationMember> query=s.entities().createQuery(invocation.getArgument(0,String.class),IdentityMigrationMember.class);
				@SuppressWarnings("unchecked") TypedQuery<IdentityMigrationMember> observed=mock(TypedQuery.class,AdditionalAnswers.delegatesTo(query));
				doAnswer(call -> { query.setParameter(call.getArgument(0,String.class),call.getArgument(1)); return observed; }).when(observed).setParameter(anyString(),any());
				doAnswer(call -> { query.setLockMode(call.getArgument(0)); return observed; }).when(observed).setLockMode(any());
				doAnswer(call -> { secondAtQuery.countDown(); return query.getResultList(); }).when(observed).getResultList();
				return observed;
			}).when(secondEntities).createQuery(anyString(),eq(IdentityMigrationMember.class));
			var first=new MemberIdentityMigrationService(s.entities(),s.transactions(),blockingCipher);
			var second=new MemberIdentityMigrationService(secondEntities,s.transactions(),cipher);
			ExecutorService pool=Executors.newFixedThreadPool(2);
			try {
				var a=pool.submit(() -> first.migrate(Set.of(11L),true));
				assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
				var b=pool.submit(() -> second.migrate(Set.of(11L),true));
				assertThat(secondAtQuery.await(10,TimeUnit.SECONDS)).isTrue();
				assertThatThrownBy(() -> b.get(300,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
				release.countDown();
				assertThat(a.get(20,TimeUnit.SECONDS).newlyMigrated()).isEqualTo(1);
				assertThat(b.get(20,TimeUnit.SECONDS).alreadyMigrated()).isEqualTo(1);
				assertThat(s.service().migrate(Set.of(11L),true).alreadyMigrated()).isEqualTo(1);
			} finally { release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5,TimeUnit.SECONDS)).isTrue(); }
		});
	}

	@Test void startupBlocksUntilExplicitMigrationWithoutCreatingFailedFlywayHistory() throws Exception {
		withDatabase(s -> {
			legacy(s.jdbc(),11,NUMBER);
			var flyway=Flyway.configure().dataSource(s.database()).javaMigrations(new V8__encrypt_legacy_member_identity()).load();
			flyway.migrate();
			var row=members(s.jdbc()); var history=history(s.jdbc());
			new ApplicationContextRunner().withUserConfiguration(IdentityMigrationConfiguration.class)
				.withBean(DataSource.class,() -> s.database()).run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure()).hasRootCauseMessage("Legacy member identity migration is required; run migrateMemberIdentity explicitly before starting insurance-api");
				});
			assertThat(members(s.jdbc())).isEqualTo(row);
			assertThat(s.jdbc().queryForObject("select count(*) from flyway_schema_history where success=0",Integer.class)).isZero();
			s.service().migrate(Set.of(11L),true);
			new ApplicationContextRunner().withUserConfiguration(IdentityMigrationConfiguration.class)
				.withBean(DataSource.class,() -> s.database()).run(context -> assertThat(context).hasNotFailed());
			assertThat(history(s.jdbc())).isEqualTo(history);
			assertThat(flyway.migrate().migrationsExecuted).isZero();
		});
	}

	@Test void realCommandPreviewsAppliesAndRejectsAnImplicitTargetWithoutLeakingSecrets() throws Exception {
		withDatabase(s -> {
			legacy(s.jdbc(),11,NUMBER);
			var before=members(s.jdbc()); var history=history(s.jdbc());
			var preview=command(s,false,false); assertThat(preview.exitCode()).isZero(); assertThat(preview.output()).contains("미리보기");
			assertThat(members(s.jdbc())).isEqualTo(before);
			assertThat(history(s.jdbc())).isEqualTo(history);
			var applied=command(s,true,false); assertThat(applied.exitCode()).isZero(); assertThat(applied.output()).contains("이관 완료");
			var migrated=members(s.jdbc());
			assertThat(command(s,true,false).exitCode()).isZero();
			assertThat(members(s.jdbc())).isEqualTo(migrated);
			assertThat(history(s.jdbc())).isEqualTo(history);
			var missingTarget=command(s,false,true); assertThat(missingTarget.exitCode()).isEqualTo(1);
			assertThat(missingTarget.output()).contains("JDBC URL");
			legacy(s.jdbc(),12,NUMBER);
			var beforeFailure=members(s.jdbc());
			// DB 드라이버 오류에 원문이 들어 있어도 별도 명령의 출력에는 노출되지 않아야 한다.
			s.jdbc().execute("create trigger reject_sensitive_write before update on member for each row signal sqlstate '45000' set message_text='"+NUMBER+"'");
			var failed=command(s,true,false,"12");
			assertThat(failed.exitCode()).isEqualTo(1);
			assertThat(failed.output()).contains("원문 오류는 출력하지 않습니다");
			assertThat(members(s.jdbc())).isEqualTo(beforeFailure);
			assertThat(history(s.jdbc())).isEqualTo(history);
		});
	}

	@Test void commandRejectsWrongSchemaHistoryBeforeAnyIdentityIsChanged() throws Exception {
		withDatabase(s -> {
			legacy(s.jdbc(),11,NUMBER);
			// 테이블 모양이 비슷해도 보험 V7 정상 이력이 없는 대상 DB는 이관하지 않는다.
			s.jdbc().update("delete from flyway_schema_history where version='7'");
			var before=members(s.jdbc()); var history=history(s.jdbc());
			var result=command(s,true,false);
			assertThat(result.exitCode()).isEqualTo(1);
			assertThat(result.output()).contains("보험 V7");
			assertThat(members(s.jdbc())).isEqualTo(before);
			assertThat(history(s.jdbc())).isEqualTo(history);
		});
	}

	private ProcessResult command(Scenario s,boolean apply,boolean missingTarget) throws Exception {
		return command(s,apply,missingTarget,"11");
	}
	private ProcessResult command(Scenario s,boolean apply,boolean missingTarget,String ids) throws Exception {
		var args=new ArrayList<>(java.util.List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Dfile.encoding=UTF-8","-cp",
			System.getProperty("identityMigrationClasspath"),MemberIdentityMigrationCommand.class.getName(),"--confirmed-domestic-member-ids="+ids));
		if (apply) args.add("--apply");
		var builder=new ProcessBuilder(args).directory(processDirectory.toFile()).redirectErrorStream(true);
		var environment=builder.environment();
		environment.put("INSURANCE_IDENTITY_ENCRYPTION_KEY",PUBLIC_TEST_KEY);
		environment.put("MYSQL_USER",s.database().getUsername()); environment.put("MYSQL_PASSWORD",s.database().getPassword());
		if (missingTarget) environment.remove("INSURANCE_IDENTITY_MIGRATION_JDBC_URL");
		else environment.put("INSURANCE_IDENTITY_MIGRATION_JDBC_URL",s.database().getUrl());
		Path output=processDirectory.resolve(UUID.randomUUID()+".log");
		Process process=builder.redirectOutput(output.toFile()).start();
		try {
			assertThat(process.waitFor(45,TimeUnit.SECONDS)).isTrue();
			String text=Files.readString(output,StandardCharsets.UTF_8);
			assertThat(text).doesNotContain(NUMBER,PUBLIC_TEST_KEY,s.database().getPassword(),"v1:","jdbc:mysql://");
			return new ProcessResult(process.exitValue(),text);
		} finally { if(process.isAlive()) { process.destroyForcibly(); process.waitFor(5,TimeUnit.SECONDS); } }
	}

	private static void legacy(JdbcTemplate jdbc,long id,String number) {
		jdbc.update("insert into member (id,login_id,password_hash,name,role,phone,resident_registration_number) values (?,?,'unchanged-hash','Existing','WORKER','01000000000',?)",id,"legacy-"+id,number);
	}
	private static java.util.List<Map<String,Object>> members(JdbcTemplate jdbc) { return jdbc.queryForList("select * from member order by id"); }
	private static java.util.List<Map<String,Object>> history(JdbcTemplate jdbc) { return jdbc.queryForList("select * from flyway_schema_history order by installed_rank"); }
	private void withDatabase(Check check) throws Exception {
		String schema="insurance_manual_identity_"+UUID.randomUUID().toString().replace("-","");
		HikariDataSource original=dataSource.unwrap(HikariDataSource.class);
		var database=new DriverManagerDataSource(original.getJdbcUrl().replaceFirst("/[^/?]+(?=\\?|$)","/"+schema),original.getUsername(),original.getPassword());
		try {
			Flyway.configure().dataSource(database).javaMigrations(new V8__encrypt_legacy_member_identity()).target("7").load().migrate();
			try { MemberIdentityMigrationCommand.requireSchema(database); } catch(RuntimeException failure) { throw new AssertionError(failure); }
			var factory=MemberIdentityMigrationCommand.entityManagerFactory(database);
			try {
				var transactions=new JpaTransactionManager(factory.getObject());
				transactions.setRollbackOnCommitFailure(true);
				var entities=SharedEntityManagerCreator.createSharedEntityManager(factory.getObject());
				check.run(new Scenario(database,new JdbcTemplate(database),entities,transactions,new MemberIdentityMigrationService(entities,transactions,cipher)));
			} finally { factory.destroy(); }
		} finally {
			try(var connection=dataSource.getConnection();var statement=connection.createStatement()) { statement.execute("drop database if exists `"+schema+"`"); }
		}
	}
	private record Scenario(DriverManagerDataSource database,JdbcTemplate jdbc,EntityManager entities,JpaTransactionManager transactions,MemberIdentityMigrationService service) {}
	private record ProcessResult(int exitCode,String output) {}
	@FunctionalInterface private interface Check { void run(Scenario scenario) throws Exception; }
}
