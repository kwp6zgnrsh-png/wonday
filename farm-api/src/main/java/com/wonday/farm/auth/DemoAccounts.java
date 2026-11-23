package com.wonday.farm.auth;

import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@Profile("farm-demo")
class DemoAccounts {
	// 데모 모드 시작 시 환경변수에서 비밀번호를 읽어 고용주와 근로자 계정을 준비한다.
	@Bean
	ApplicationRunner registerDemoAccounts(MemberRepository members, PasswordEncoder encoder, TransactionTemplate transaction,
			@Value("${FARM_DEMO_EMPLOYER_PASSWORD:}") String employerPassword,
			@Value("${FARM_DEMO_WORKER_PASSWORD:}") String workerPassword) {
		return args -> {
			// 두 비밀번호를 먼저 확인하고 해시한 뒤, 계정 두 개를 같은 트랜잭션으로 저장한다.
			String employerHash = encode(encoder, employerPassword, "FARM_DEMO_EMPLOYER_PASSWORD");
			String workerHash = encode(encoder, workerPassword, "FARM_DEMO_WORKER_PASSWORD");
			try {
				registerMissingAccounts(members, transaction, employerHash, workerHash);
			} catch (DataIntegrityViolationException concurrentInsert) {
				// 동시에 데모 모드를 실행해 등록이 겹치면 롤백 후 새 트랜잭션에서 다시 조회한다.
				registerMissingAccounts(members, transaction, employerHash, workerHash);
			}
		};
	}

	private static String encode(PasswordEncoder encoder, String password, String property) {
		// BCrypt는 UTF-8로 인코딩한 비밀번호를 최대 72바이트까지 처리한다.
		if (password.isBlank() || password.getBytes(StandardCharsets.UTF_8).length > 72) {
			throw new IllegalStateException(property + " must be non-blank and at most 72 UTF-8 bytes");
		}
		return encoder.encode(password);
	}

	private static void registerMissingAccounts(MemberRepository members, TransactionTemplate transaction,
			String employerHash, String workerHash) {
		transaction.executeWithoutResult(status -> {
			insertIfMissing(members, "demo-employer", "데모 고용주", MemberRole.EMPLOYER, employerHash);
			insertIfMissing(members, "demo-worker", "데모 근로자", MemberRole.WORKER, workerHash);
		});
	}

	private static void insertIfMissing(MemberRepository members, String loginId, String name,
			MemberRole role, String passwordHash) {
		// 재실행 때 같은 로그인 ID가 있으면 기존 행의 ID와 비밀번호를 보존한다.
		// 연락처 없는 데모 계정도 등록할 수 있도록 V2에서 phone을 선택 항목으로 바꿨다.
		if (!members.existsByLoginId(loginId)) {
			members.save(new Member(loginId, passwordHash, name, role));
		}
	}
}
