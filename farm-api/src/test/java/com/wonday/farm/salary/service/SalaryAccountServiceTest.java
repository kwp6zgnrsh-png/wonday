package com.wonday.farm.salary.service;

import static com.wonday.farm.salary.testsupport.SalaryAccountTestData.KEY;
import static com.wonday.farm.salary.testsupport.SalaryAccountTestData.member;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import com.wonday.farm.member.Member;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.signup.testsupport.SignupTestData;
import com.wonday.farm.salary.crypto.SalaryAccountCrypto;
import com.wonday.farm.salary.crypto.SalaryAccountCryptoUnavailableException;
import com.wonday.farm.salary.dto.SalaryAccountRequest;
import com.wonday.farm.salary.dto.SalaryAccountResponse;
import com.wonday.farm.salary.entity.MemberSalaryAccount;
import com.wonday.farm.salary.exception.SalaryAccountException;
import com.wonday.farm.salary.repository.MemberSalaryAccountRepository;
import com.wonday.farm.salary.testsupport.SalaryAccountTestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;

// MemberRepository·MemberSalaryAccountRepository는 mock이다. 계좌 저장소는 메모리 참조 하나로 흉내 낸다.
// 순차 호출로 버전 규칙만 본다. 실제 MySQL 잠금(PESSIMISTIC_WRITE)·REPEATABLE_READ 스냅샷·동시 요청·롤백은 검증하지 않는다.
// SalaryAccountCrypto는 테스트 전용 키의 실제 객체를 spy로 감싼다. Clock은 나노초가 있는 고정 시각이다.
@DisplayName("급여 계좌 서비스 (Repository mock, 순차 호출)")
class SalaryAccountServiceTest {
	private static final long ID = 101L;
	private static final Instant T1 = Instant.parse("2026-11-26T01:02:03.123456789Z");
	private static final Instant T2 = Instant.parse("2026-11-27T04:05:06.987654321Z");

	private MemberRepository members;
	private MemberSalaryAccountRepository accounts;
	private SalaryAccountCrypto crypto;
	private final AtomicReference<Instant> now = new AtomicReference<>(T1);
	private final AtomicReference<MemberSalaryAccount> stored = new AtomicReference<>();
	private Member member;
	private SalaryAccountService service;

	@BeforeEach
	void setUp() {
		members = mock(MemberRepository.class);
		accounts = mock(MemberSalaryAccountRepository.class);
		crypto = spy(SalaryAccountTestData.crypto());
		member = member(ID, "WORKER", 0);
		given(members.findById(ID)).willAnswer(invocation -> Optional.of(member));
		given(members.findByIdForSalaryAccountUpdate(ID)).willAnswer(invocation -> Optional.of(member));
		given(accounts.findById(ID)).willAnswer(invocation -> Optional.ofNullable(stored.get()));
		given(accounts.saveAndFlush(any())).willAnswer(invocation -> {
			stored.set(invocation.getArgument(0));
			return invocation.getArgument(0);
		});
		org.mockito.BDDMockito.willAnswer(invocation -> { stored.set(null); return null; }).given(accounts).delete(any());
		Clock clock = new Clock() {
			@Override public ZoneId getZone() { return ZoneId.of("Asia/Seoul"); }
			@Override public Clock withZone(ZoneId zone) { return this; }
			@Override public Instant instant() { return now.get(); }
		};
		service = new SalaryAccountService(members, accounts, crypto, clock);
	}

	private static SalaryAccountRequest request(String bank, String number, String holder, Long version) {
		return new SalaryAccountRequest(bank, number, holder, version);
	}

	private static SalaryAccountException failure(Runnable call) {
		Throwable thrown = catchThrowable(call::run);
		assertThat(thrown).isInstanceOf(SalaryAccountException.class);
		return (SalaryAccountException) thrown;
	}

	private static void assertStatus(SalaryAccountException exception, HttpStatus status, String code) {
		assertThat(exception.status()).isEqualTo(status);
		assertThat(exception.code()).isEqualTo(code);
	}

	@Nested
	@DisplayName("조회")
	class Find {
		@Test
		@DisplayName("미등록: registered=false·null 필드·회원의 현재 version, 잠금 없는 조회, 복호화 없음")
		void unregistered() {
			member = member(ID, "WORKER", 7);
			assertThat(service.findMine(ID)).isEqualTo(new SalaryAccountResponse(false, null, null, null, 7));
			verify(members).findById(ID);
			verify(members, never()).findByIdForSalaryAccountUpdate(anyLong());
			verify(crypto, never()).maskedAccountNumber(anyLong(), any(), any());
		}

		@Test
		@DisplayName("미등록 조회는 계좌 키가 없어도 200용 응답")
		void unregisteredWithoutKey() {
			var noKey = new SalaryAccountService(members, accounts, new SalaryAccountCrypto(""), Clock.systemUTC());
			assertThat(noKey.findMine(ID).registered()).isFalse();
		}

		@Test
		@DisplayName("등록: 은행·마스킹 번호·예금주·version, 본인 ID로만 조회")
		void registered() {
			member = member(ID, "WORKER", 3);
			stored.set(new MemberSalaryAccount(ID, "020", crypto.encrypt(ID, "020", "100200300400"), "TEST WORKER",
				LocalDateTime.parse("2026-11-01T00:00:00")));

			assertThat(service.findMine(ID)).isEqualTo(new SalaryAccountResponse(true, "020", "********0400", "TEST WORKER", 3));
			verify(accounts).findById(ID);
		}

		@Test
		@DisplayName("등록 계좌의 키 오류·다른 회원 암호문은 정상 응답이 아니라 암호화 예외(503용)")
		void registeredUnavailable() {
			stored.set(new MemberSalaryAccount(ID, "020", crypto.encrypt(999L, "020", "100200300400"), "TEST WORKER",
				LocalDateTime.parse("2026-11-01T00:00:00")));
			assertThat(catchThrowable(() -> service.findMine(ID))).isInstanceOf(SalaryAccountCryptoUnavailableException.class);

			stored.set(new MemberSalaryAccount(ID, "020", crypto.encrypt(ID, "020", "1234"), "A", LocalDateTime.parse("2026-11-01T00:00:00")));
			var noKey = new SalaryAccountService(members, accounts, new SalaryAccountCrypto(""), Clock.systemUTC());
			assertThat(catchThrowable(() -> noKey.findMine(ID))).isInstanceOf(SalaryAccountCryptoUnavailableException.class);
		}
	}

	@Nested
	@DisplayName("접근 거절 403 — 저장·삭제 없음")
	class Forbidden {
		static Stream<Arguments> denied() {
			return Stream.of(Arguments.of("EMPLOYER"), Arguments.of("ADMIN"), Arguments.of("worker"), Arguments.of((Object) null));
		}

		@ParameterizedTest(name = "DB 역할 [{0}]")
		@MethodSource("denied")
		@DisplayName("DB의 현재 역할이 근로자가 아니면 조회·등록·해제 모두 403")
		void roleChanged(String role) {
			member = member(ID, role, 0);
			assertStatus(failure(() -> service.findMine(ID)), HttpStatus.FORBIDDEN, "WORKER_ROLE_REQUIRED");
			assertStatus(failure(() -> service.replace(ID, request("020", "1234", "A", 0L))), HttpStatus.FORBIDDEN, "WORKER_ROLE_REQUIRED");
			assertStatus(failure(() -> service.remove(ID, 0)), HttpStatus.FORBIDDEN, "WORKER_ROLE_REQUIRED");
			verify(accounts, never()).saveAndFlush(any());
			verify(accounts, never()).delete(any());
			verify(accounts, never()).findById(anyLong());
		}

		@Test
		@DisplayName("DB 회원 없음(삭제 계정)·회원 ID 0 이하도 403, 계좌 저장소 접근 없음")
		void missing() {
			given(members.findById(ID)).willReturn(Optional.empty());
			given(members.findByIdForSalaryAccountUpdate(ID)).willReturn(Optional.empty());
			assertStatus(failure(() -> service.findMine(ID)), HttpStatus.FORBIDDEN, "WORKER_ROLE_REQUIRED");
			assertStatus(failure(() -> service.replace(ID, request("020", "1234", "A", 0L))), HttpStatus.FORBIDDEN, "WORKER_ROLE_REQUIRED");
			assertStatus(failure(() -> service.remove(ID, 0)), HttpStatus.FORBIDDEN, "WORKER_ROLE_REQUIRED");
			assertStatus(failure(() -> service.findMine(0)), HttpStatus.FORBIDDEN, "WORKER_ROLE_REQUIRED");
			assertStatus(failure(() -> service.remove(-1, 0)), HttpStatus.FORBIDDEN, "WORKER_ROLE_REQUIRED");
			verifyNoInteractions(accounts);
		}
	}

	@Nested
	@DisplayName("등록·교체")
	class Replace {
		@Test
		@DisplayName("신규 등록: 회원 잠금 조회, 정규화(하이픈 제거·예금주 공백 제거), 회원·은행 결합 암호문, UTC 마이크로초 시각, version 0→1")
		void create() throws Exception {
			SalaryAccountResponse response = service.replace(ID, request("020", "1002-0030-0400", "  TEST WORKER  ", 0L));

			assertThat(response).isEqualTo(new SalaryAccountResponse(true, "020", "********0400", "TEST WORKER", 1));
			MemberSalaryAccount saved = stored.get();
			assertThat(saved.getMemberId()).isEqualTo(ID);
			assertThat(saved.bankCode()).isEqualTo("020");
			assertThat(saved.accountHolderName()).isEqualTo("TEST WORKER");
			assertThat(SignupTestData.decryptAccount(saved.ciphertextForDecryption(), ID, "020", KEY)).isEqualTo("100200300400");
			assertThat(saved.ciphertextForDecryption()).doesNotContain("100200300400", "1002-0030-0400");
			LocalDateTime expected = LocalDateTime.parse("2026-11-26T01:02:03.123456");
			assertThat(saved.createdAt()).isEqualTo(expected);
			assertThat(saved.updatedAt()).isEqualTo(expected);
			assertThat(member.salaryAccountVersion()).isEqualTo(1);

			verify(members).findByIdForSalaryAccountUpdate(ID);
			verify(members, never()).findById(anyLong());
			var order = inOrder(members, accounts);
			order.verify(members).findByIdForSalaryAccountUpdate(ID);
			order.verify(accounts).saveAndFlush(saved);
			order.verify(members).flush();
		}

		@Test
		@DisplayName("교체: 같은 엔티티를 갱신해 createdAt 보존·updatedAt 갱신, 새 은행 코드로 다시 결합, version 증가")
		void replaceExisting() throws Exception {
			service.replace(ID, request("020", "100200300400", "TEST WORKER", 0L));
			MemberSalaryAccount first = stored.get();
			now.set(T2);

			SalaryAccountResponse response = service.replace(ID, request("004", "0000-0000-1", "NEW HOLDER", 1L));

			assertThat(stored.get()).isSameAs(first);
			assertThat(first.createdAt()).isEqualTo(LocalDateTime.parse("2026-11-26T01:02:03.123456"));
			assertThat(first.updatedAt()).isEqualTo(LocalDateTime.parse("2026-11-27T04:05:06.987654"));
			assertThat(first.bankCode()).isEqualTo("004");
			assertThat(SignupTestData.decryptAccount(first.ciphertextForDecryption(), ID, "004", KEY)).isEqualTo("000000001");
			assertThat(response).isEqualTo(new SalaryAccountResponse(true, "004", "*****0001", "NEW HOLDER", 2));
		}

		@Test
		@DisplayName("같은 값의 유효한 PUT도 매번 version 증가 (0→1→2→3)")
		void sameValueAdvances() {
			for (long version = 0; version < 3; version++) {
				assertThat(service.replace(ID, request("020", "1234", "A", version)).version()).isEqualTo(version + 1);
			}
		}

		@ParameterizedTest(name = "현재 5, 요청 {0}")
		@ValueSource(longs = {4, 0, 6})
		@DisplayName("버전 불일치(오래된·미래)는 409, 암호화·저장·flush 없음, 회원 버전·기존 계좌 유지")
		void staleVersion(long requested) {
			member = member(ID, "WORKER", 5);
			stored.set(new MemberSalaryAccount(ID, "020", crypto.encrypt(ID, "020", "100200300400"), "OLD", LocalDateTime.parse("2026-11-01T00:00:00")));
			MemberSalaryAccount before = stored.get();
			String ciphertext = before.ciphertextForDecryption();
			org.mockito.Mockito.clearInvocations(crypto);

			assertStatus(failure(() -> service.replace(ID, request("004", "1234", "NEW", requested))), HttpStatus.CONFLICT,
				"SALARY_ACCOUNT_VERSION_CONFLICT");

			assertThat(member.salaryAccountVersion()).isEqualTo(5);
			assertThat(before.bankCode()).isEqualTo("020");
			assertThat(before.ciphertextForDecryption()).isEqualTo(ciphertext);
			assertThat(before.accountHolderName()).isEqualTo("OLD");
			verify(crypto, never()).encrypt(anyLong(), anyString(), anyString());
			verify(accounts, never()).saveAndFlush(any());
			verify(members, never()).flush();
		}

		static Stream<Arguments> invalidRequests() {
			return Stream.of(
				Arguments.of("요청 null", null),
				Arguments.of("version null", request("020", "1234", "A", null)),
				Arguments.of("version 음수", request("020", "1234", "A", -1L)),
				Arguments.of("은행 2자리", request("20", "1234", "A", 0L)),
				Arguments.of("은행 문자", request("0a0", "1234", "A", 0L)),
				Arguments.of("은행 null", request(null, "1234", "A", 0L)),
				Arguments.of("번호 마스킹 문자열", request("020", "********0400", "A", 0L)),
				Arguments.of("번호 31자리", request("020", "1".repeat(31), "A", 0L)),
				Arguments.of("번호 하이픈만", request("020", "---", "A", 0L)),
				Arguments.of("번호 공백 포함", request("020", "1002 0030", "A", 0L)),
				Arguments.of("번호 전각 숫자", request("020", "１２３４", "A", 0L)),
				Arguments.of("번호 빈 값", request("020", "", "A", 0L)),
				Arguments.of("예금주 공백만", request("020", "1234", "   ", 0L)),
				Arguments.of("예금주 101자", request("020", "1234", "가".repeat(101), 0L)),
				Arguments.of("예금주 null", request("020", "1234", null, 0L)));
		}

		@ParameterizedTest(name = "{0}")
		@MethodSource("invalidRequests")
		@DisplayName("잘못된 입력은 400이고 회원 잠금·암호화·저장이 실행되지 않는다")
		void invalid(String label, SalaryAccountRequest request) {
			assertStatus(failure(() -> service.replace(ID, request)), HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
			verifyNoInteractions(members, accounts);
			verify(crypto, never()).encrypt(anyLong(), anyString(), anyString());
		}

		@Test
		@DisplayName("경계 허용: 번호 1자리·30자리·하이픈 포함 100자, 예금주 100자")
		void boundariesAccepted() {
			assertThat(service.replace(ID, request("020", "0", "A", 0L)).maskedAccountNumber()).isEqualTo("*");
			assertThat(service.replace(ID, request("020", "1".repeat(30), "가".repeat(100), 1L)).maskedAccountNumber())
				.isEqualTo("*".repeat(26) + "1111");
			String hyphenated = "1-".repeat(30) + "-".repeat(40);
			assertThat(hyphenated).hasSize(100);
			assertThat(service.replace(ID, request("020", hyphenated, "A", 2L)).maskedAccountNumber()).isEqualTo("*".repeat(26) + "1111");
		}

		@Test
		@DisplayName("키 미설정: 버전이 맞아도 503용 예외, 저장·버전 변경 없음")
		void noKey() {
			var noKey = new SalaryAccountService(members, accounts, new SalaryAccountCrypto(""), Clock.systemUTC());
			assertThat(catchThrowable(() -> noKey.replace(ID, request("020", "1234", "A", 0L))))
				.isInstanceOf(SalaryAccountCryptoUnavailableException.class);
			assertThat(member.salaryAccountVersion()).isZero();
			verify(accounts, never()).saveAndFlush(any());
		}

		@Test
		@DisplayName("version이 Long 최대값이면 503 (증가 불가), 저장 없음")
		void maxVersion() {
			member = member(ID, "WORKER", Long.MAX_VALUE);
			assertStatus(failure(() -> service.replace(ID, request("020", "1234", "A", Long.MAX_VALUE))), HttpStatus.SERVICE_UNAVAILABLE,
				"SERVICE_UNAVAILABLE");
			verify(accounts, never()).saveAndFlush(any());
		}

		@Test
		@DisplayName("저장 예외는 성공 응답으로 바뀌지 않고 그대로 전파 (실제 롤백은 미검증)")
		void saveFailure() {
			var failure = new DataAccessResourceFailureException("Communications link failure 100200300400");
			given(accounts.saveAndFlush(any())).willThrow(failure);
			assertThat(catchThrowable(() -> service.replace(ID, request("020", "100200300400", "A", 0L)))).isSameAs(failure);
			verify(members, never()).flush();
		}
	}

	@Nested
	@DisplayName("해제")
	class Remove {
		@Test
		@DisplayName("등록 + 버전 일치: 회원 잠금 후 삭제, version 증가, flush, 복호화·암호화 없음")
		void removeRegistered() {
			service.replace(ID, request("020", "100200300400", "A", 0L));
			MemberSalaryAccount account = stored.get();
			org.mockito.Mockito.clearInvocations(crypto, members, accounts);

			service.remove(ID, 1);

			assertThat(stored.get()).isNull();
			assertThat(member.salaryAccountVersion()).isEqualTo(2);
			verify(members).findByIdForSalaryAccountUpdate(ID);
			verify(accounts).delete(account);
			verify(accounts).flush();
			verify(members).flush();
			verifyNoInteractions(crypto);
		}

		@Test
		@DisplayName("해제는 키가 없어도 된다 (복호화 불필요)")
		void removeWithoutKey() {
			stored.set(new MemberSalaryAccount(ID, "020", crypto.encrypt(ID, "020", "1234"), "A", LocalDateTime.parse("2026-11-01T00:00:00")));
			new SalaryAccountService(members, accounts, new SalaryAccountCrypto(""), Clock.systemUTC()).remove(ID, 0);
			assertThat(stored.get()).isNull();
			assertThat(member.salaryAccountVersion()).isEqualTo(1);
		}

		@ParameterizedTest(name = "현재 3, 요청 {0}")
		@ValueSource(longs = {2, 0, 4})
		@DisplayName("등록 + 버전 불일치: 409, 삭제·버전 변경 없음")
		void staleRegistered(long requested) {
			member = member(ID, "WORKER", 3);
			stored.set(new MemberSalaryAccount(ID, "020", crypto.encrypt(ID, "020", "1234"), "A", LocalDateTime.parse("2026-11-01T00:00:00")));
			assertStatus(failure(() -> service.remove(ID, requested)), HttpStatus.CONFLICT, "SALARY_ACCOUNT_VERSION_CONFLICT");
			assertThat(stored.get()).isNotNull();
			assertThat(member.salaryAccountVersion()).isEqualTo(3);
			verify(accounts, never()).delete(any());
		}

		@ParameterizedTest(name = "현재 미등록 version 3, 요청 {0}")
		@ValueSource(longs = {0, 2, 3, 99})
		@DisplayName("현재 미등록: 어떤 유효 버전이어도 성공(204용), 버전 증가·삭제·flush 없음 — 반복 해제 포함")
		void unregistered(long requested) {
			member = member(ID, "WORKER", 3);
			service.remove(ID, requested);
			service.remove(ID, requested);
			assertThat(member.salaryAccountVersion()).isEqualTo(3);
			verify(accounts, never()).delete(any());
			verify(members, never()).flush();
		}

		@Test
		@DisplayName("음수 버전은 미등록이어도 400, 회원 조회 없음")
		void negative() {
			assertStatus(failure(() -> service.remove(ID, -1)), HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
			verifyNoInteractions(members, accounts);
		}

		@Test
		@DisplayName("등록 → 해제 → 재등록 → 오래된 해제: 버전은 0으로 돌아가지 않고 이어진다 (1 → 2 → 3, 해제 409)")
		void lifecycle() {
			assertThat(service.replace(ID, request("020", "1234", "A", 0L)).version()).isEqualTo(1);
			service.remove(ID, 1);
			assertThat(service.findMine(ID)).isEqualTo(new SalaryAccountResponse(false, null, null, null, 2));
			assertStatus(failure(() -> service.replace(ID, request("020", "1234", "A", 0L))), HttpStatus.CONFLICT,
				"SALARY_ACCOUNT_VERSION_CONFLICT");
			assertThat(service.replace(ID, request("020", "12345", "A", 2L)).version()).isEqualTo(3);
			assertStatus(failure(() -> service.remove(ID, 1)), HttpStatus.CONFLICT, "SALARY_ACCOUNT_VERSION_CONFLICT");
			assertThat(service.findMine(ID).maskedAccountNumber()).isEqualTo("*2345");
		}

		@Test
		@DisplayName("삭제 예외는 그대로 전파된다 (실제 롤백·버전 원복은 미검증)")
		void deleteFailure() {
			stored.set(new MemberSalaryAccount(ID, "020", crypto.encrypt(ID, "020", "1234"), "A", LocalDateTime.parse("2026-11-01T00:00:00")));
			var failure = new DataAccessResourceFailureException("db down");
			willThrow(failure).given(accounts).delete(any());
			assertThat(catchThrowable(() -> service.remove(ID, 0))).isSameAs(failure);
			verify(members, never()).flush();
		}
	}
}
