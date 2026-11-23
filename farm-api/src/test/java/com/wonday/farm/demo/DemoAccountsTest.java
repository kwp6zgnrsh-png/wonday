package com.wonday.farm.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;

import com.wonday.farm.member.Member;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class DemoAccountsTest {
	private final MemberRepository members = mock(MemberRepository.class);
	private final PasswordEncoder encoder = mock(PasswordEncoder.class);
	private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
	private final TransactionTemplate transaction = new TransactionTemplate(transactionManager);

	DemoAccountsTest() {
		given(encoder.encode(any())).willAnswer(invocation -> "hash:" + invocation.getArgument(0));
	}

	@Test
	void registersBothAccountsWithHashedPasswordsInOneTransaction() throws Exception {
		run("employer-pass", "worker-pass");

		ArgumentCaptor<Member> saved = ArgumentCaptor.forClass(Member.class);
		verify(members, times(2)).save(saved.capture());
		List<Member> accounts = saved.getAllValues();
		assertThat(accounts).extracting(Member::getLoginId).containsExactly("demo-employer", "demo-worker");
		assertThat(accounts).extracting(Member::passwordHash)
			.containsExactly("hash:employer-pass", "hash:worker-pass");
		assertThat(accounts).extracting(member -> member.memberRole().orElseThrow())
			.containsExactly(MemberRole.EMPLOYER, MemberRole.WORKER);
		assertThat(accounts).extracting(Member::displayName).containsExactly("데모 고용주", "데모 근로자");
		verify(transactionManager, times(1)).commit(any());
	}

	@Test
	void keepsExistingAccountsOnRestart() throws Exception {
		given(members.existsByLoginId("demo-employer")).willReturn(true);
		given(members.existsByLoginId("demo-worker")).willReturn(true);

		run("employer-pass", "worker-pass");

		verify(members, never()).save(any());
	}

	@Test
	void registersOnlyMissingAccount() throws Exception {
		given(members.existsByLoginId("demo-employer")).willReturn(true);

		run("employer-pass", "worker-pass");

		ArgumentCaptor<Member> saved = ArgumentCaptor.forClass(Member.class);
		verify(members).save(saved.capture());
		assertThat(saved.getValue().getLoginId()).isEqualTo("demo-worker");
	}

	@Test
	void retriesInNewTransactionWhenConcurrentStartInsertedFirst() throws Exception {
		// 첫 시도에서 다른 인스턴스가 먼저 등록해 고유 키 충돌이 나면, 롤백 후 다시 조회해 기존 행을 유지한다.
		given(members.existsByLoginId("demo-employer")).willReturn(false, true);
		given(members.existsByLoginId("demo-worker")).willReturn(true);
		given(members.save(any())).willThrow(new DataIntegrityViolationException("duplicate login_id"));

		run("employer-pass", "worker-pass");

		verify(members, times(1)).save(any());
		verify(transactionManager).rollback(any());
		verify(transactionManager).commit(any());
	}

	@Test
	void rejectsBlankEmployerPasswordBeforeTouchingDatabase() {
		assertThatThrownBy(() -> run(" ", "worker-pass"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("FARM_DEMO_EMPLOYER_PASSWORD");

		verify(members, never()).existsByLoginId(any());
		verify(encoder, never()).encode(any());
	}

	@Test
	void rejectsWorkerPasswordOverBcryptLimit() {
		String tooLong = "가".repeat(25); // UTF-8 75바이트

		assertThatThrownBy(() -> run("employer-pass", tooLong))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("FARM_DEMO_WORKER_PASSWORD")
			.hasMessageContaining("72");

		verify(members, never()).save(any());
	}

	private void run(String employerPassword, String workerPassword) throws Exception {
		ApplicationRunner runner = new DemoAccounts()
			.registerDemoAccounts(members, encoder, transaction, employerPassword, workerPassword);
		runner.run(new DefaultApplicationArguments());
	}
}
