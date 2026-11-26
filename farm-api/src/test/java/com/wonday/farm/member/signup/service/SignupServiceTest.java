package com.wonday.farm.member.signup.service;

import static com.wonday.farm.member.signup.testsupport.SignupTestData.ACCOUNT_KEY;
import static com.wonday.farm.member.signup.testsupport.SignupTestData.IDENTITY_NORMALIZED;
import static com.wonday.farm.member.signup.testsupport.SignupTestData.account;
import static com.wonday.farm.member.signup.testsupport.SignupTestData.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.function.UnaryOperator;

import com.wonday.farm.member.Member;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import com.wonday.farm.member.identity.IdentityCrypto;
import com.wonday.farm.member.identity.IdentityCryptoUnavailableException;
import com.wonday.farm.member.identity.IdentityType;
import com.wonday.farm.member.identity.NationalityType;
import com.wonday.farm.member.identity.testsupport.IdentityTestKeys;
import com.wonday.farm.member.signup.dto.SignupRequest;
import com.wonday.farm.member.signup.dto.SignupResponse;
import com.wonday.farm.member.signup.dto.SignupSalaryAccountRequest;
import com.wonday.farm.member.signup.exception.SignupException;
import com.wonday.farm.salary.crypto.SalaryAccountCrypto;
import com.wonday.farm.salary.crypto.SalaryAccountCryptoUnavailableException;
import com.wonday.farm.salary.entity.MemberSalaryAccount;
import com.wonday.farm.salary.repository.MemberSalaryAccountRepository;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

// SignupService를 직접 생성한 mock 단위 테스트. Spring 트랜잭션 프록시를 거치지 않으므로 실제 DB 롤백은 증명하지 않는다.
// 저장 순서·예외 전파·@Transactional 선언만 확인한다. Repository·PasswordEncoder는 mock, 암호화는 테스트 키의 실제 객체다.
@DisplayName("회원가입 서비스 (SignupService)")
class SignupServiceTest {
	private static final long NEW_ID = 101L;

	private final MemberRepository members = mock(MemberRepository.class);
	private final MemberSalaryAccountRepository accounts = mock(MemberSalaryAccountRepository.class);
	private final PasswordEncoder passwords = mock(PasswordEncoder.class);
	private final IdentityCrypto identities = IdentityTestKeys.crypto();
	private final SalaryAccountCrypto accountCrypto = new SalaryAccountCrypto(ACCOUNT_KEY);
	private final Clock clock = Clock.fixed(Instant.parse("2026-11-26T01:02:03.123456789Z"), ZoneId.of("Asia/Seoul"));
	private final SignupService service = new SignupService(members, accounts, passwords, identities, accountCrypto, clock);

	@BeforeEach
	void setUp() {
		given(passwords.encode(anyString())).willAnswer(invocation -> "{bcrypt}encoded");
		given(members.saveAndFlush(any())).willAnswer(invocation -> {
			Member member = invocation.getArgument(0);
			ReflectionTestUtils.setField(member, "id", NEW_ID);
			return member;
		});
		given(accounts.saveAndFlush(any())).willAnswer(invocation -> invocation.getArgument(0));
	}

	@Nested
	@DisplayName("정상 가입")
	class Success {
		@ParameterizedTest(name = "{0} / {1}")
		@CsvSource({"WORKER, DOMESTIC, RESIDENT_REGISTRATION_NUMBER", "WORKER, FOREIGN, FOREIGN_REGISTRATION_NUMBER",
			"EMPLOYER, DOMESTIC, RESIDENT_REGISTRATION_NUMBER", "EMPLOYER, FOREIGN, FOREIGN_REGISTRATION_NUMBER"})
		@DisplayName("역할·국적 조합마다 정규화 값과 서버가 정한 번호 종류로 저장하고 응답은 5필드만 담는다")
		void roleAndNationality(MemberRole role, NationalityType nationality, IdentityType type) {
			SignupResponse response = service.signup(request(role.name(), nationality.name(), null));

			Member saved = savedMember();
			assertThat(field(saved, "loginId")).isEqualTo("worker-01");
			assertThat(field(saved, "name")).isEqualTo("TEST WORKER");
			assertThat(field(saved, "phone")).isEqualTo("01012345678");
			assertThat(saved.memberRole()).contains(role);
			assertThat(saved.nationalityType()).contains(nationality);
			assertThat(saved.identityType()).contains(type);
			assertThat(field(saved, "identityHmac")).isEqualTo(identities.blindIndex(IDENTITY_NORMALIZED));
			assertThat(identities.decryptForMigration(NEW_ID, type, (String) field(saved, "identityCiphertext"))).isEqualTo(IDENTITY_NORMALIZED);
			assertThat(response).isEqualTo(new SignupResponse(NEW_ID, "worker-01", "TEST WORKER", role, nationality));
			assertThat(java.util.Arrays.stream(SignupResponse.class.getRecordComponents()).map(c -> c.getName()))
				.containsExactly("id", "loginId", "name", "role", "nationalityType");
		}

		@Test
		@DisplayName("비밀번호는 trim 없이 원문 그대로 인코더에 전달되고 평문은 저장되지 않는다")
		void passwordEncoded() {
			service.signup(new SignupRequest("id", "  space pass  ", "이름", "0101234567", "WORKER", "DOMESTIC", "1111111111111", null));

			verify(passwords).encode("  space pass  ");
			assertThat(savedMember().passwordHash()).isEqualTo("{bcrypt}encoded").doesNotContain("space pass");
		}

		@Test
		@DisplayName("loginId·이름은 앞뒤만 정리하고 내부 공백·대소문자·외국어를 유지, 전화·번호의 앞자리 0 유지")
		void normalization() {
			service.signup(new SignupRequest("  Worker 01 ", "pw", "　Nguyen  Van A ", " 010 0000-0000 ", "WORKER", "FOREIGN",
				" 000000 - 0000000 ", null));

			Member saved = savedMember();
			assertThat(field(saved, "loginId")).isEqualTo("Worker 01");
			assertThat(field(saved, "name")).isEqualTo("Nguyen  Van A");
			assertThat(field(saved, "phone")).isEqualTo("01000000000");
			assertThat(field(saved, "identityHmac")).isEqualTo(identities.blindIndex("0000000000000"));
			verify(members).existsByLoginId("Worker 01");
			verify(members).countLegacyIdentityNumber("0000000000000");
		}

		@Test
		@DisplayName("계좌 생략: 계좌 저장 없음, 계좌 키가 없어도 가입 성공")
		void withoutAccount() {
			var noAccountKey = new SignupService(members, accounts, passwords, identities, new SalaryAccountCrypto(""), clock);

			noAccountKey.signup(request("WORKER", "DOMESTIC", null));

			verifyNoInteractions(accounts);
			verify(members).flush();
		}

		@Test
		@DisplayName("계좌 포함: 같은 회원 ID에 은행·예금주(앞뒤 공백 제거)·암호문·UTC 마이크로초 시각을 저장하고 응답에 계좌는 없다")
		void withAccount() throws Exception {
			SignupResponse response = service.signup(request("WORKER", "FOREIGN", account()));

			ArgumentCaptor<MemberSalaryAccount> captor = ArgumentCaptor.forClass(MemberSalaryAccount.class);
			verify(accounts).saveAndFlush(captor.capture());
			MemberSalaryAccount saved = captor.getValue();
			assertThat(saved.getMemberId()).isEqualTo(NEW_ID);
			assertThat(saved.bankCode()).isEqualTo("020");
			assertThat(saved.accountHolderName()).isEqualTo("TEST WORKER");
			assertThat(saved.createdAt()).isEqualTo(LocalDateTime.parse("2026-11-26T01:02:03.123456")).isEqualTo(saved.updatedAt());
			String ciphertext = (String) ReflectionTestUtils.getField(saved, "accountNumberCiphertext");
			assertThat(ciphertext).doesNotContain("100200300400");
			assertThat(com.wonday.farm.member.signup.testsupport.SignupTestData.decryptAccount(ciphertext, NEW_ID, "020", ACCOUNT_KEY)).isEqualTo("100200300400");
			assertThat(response.toString()).doesNotContain("100200300400", "1002-0030-0400", IDENTITY_NORMALIZED);
		}

		@Test
		@DisplayName("저장 순서: 키·중복 확인 → 회원 saveAndFlush(ID 발급) → 계좌 saveAndFlush → 최종 flush")
		void order() {
			service.signup(request("WORKER", "DOMESTIC", account()));

			var order = inOrder(members, accounts);
			order.verify(members).existsByLoginId("worker-01");
			order.verify(members).existsByIdentityHmac(identities.blindIndex(IDENTITY_NORMALIZED));
			order.verify(members).countLegacyIdentityNumber(IDENTITY_NORMALIZED);
			order.verify(members).saveAndFlush(any());
			order.verify(accounts).saveAndFlush(any());
			order.verify(members).flush();
		}

		@Test
		@DisplayName("비밀번호 72바이트 경계 허용 (ASCII 72자, 한글 24자 = 72바이트)")
		void passwordBoundary() {
			service.signup(withPassword("a".repeat(72)));
			service.signup(withPassword("가".repeat(24)));
			verify(passwords).encode("a".repeat(72));
			verify(passwords).encode("가".repeat(24));
		}
	}

	@Nested
	@DisplayName("입력 거절 — 키 확인·조회·저장을 시작하지 않음")
	class InvalidInput {
		@Test
		@DisplayName("비밀번호 73바이트(ASCII 73자, 한글 24자+1, 한글 25자)·공백만은 거절")
		void passwordOverLimit() {
			for (String password : new String[] {"a".repeat(73), "가".repeat(24) + "a", "가".repeat(25), "   ", ""}) {
				assertInvalid(withPassword(password));
			}
		}

		@ParameterizedTest(name = "{0}")
		@ValueSource(strings = {"phone=010123456", "phone=010123456789", "phone=010-1234-567a", "phone=０１０１２３４５６７８",
			"phone=+821012345678", "phone=010_1234_5678", "identity=111111-111111", "identity=11111111111111",
			"identity=111111_1111111", "identity=１１１１１１１１１１１１１", "role=worker", "role=ADMIN", "role= WORKER",
			"nationality=domestic", "nationality=KOREAN", "nationality=Foreign", "loginId=   ", "name=　 ",
			"account=bank:02", "account=number:-", "account=number:1002 0030", "account=number:" + "1111111111111111111111111111111",
			"account=holder: ", "loginId=\u0001\u0002"})
		@DisplayName("전화·식별번호·역할·국적 대소문자·공백 이름·계좌 형식 오류")
		void invalid(String change) {
			assertInvalid(changed(change));
		}

		@Test
		@DisplayName("null 요청·null 필드도 INVALID_REQUEST (Bean Validation 우회 대비)")
		void nulls() {
			assertInvalid(null);
			assertInvalid(new SignupRequest(null, "pw", "n", "0101234567", "WORKER", "DOMESTIC", "1111111111111", null));
			assertInvalid(new SignupRequest("id", "pw", "n", "0101234567", null, "DOMESTIC", "1111111111111", null));
			assertInvalid(new SignupRequest("id", "pw", "n", "0101234567", "WORKER", "DOMESTIC", null, null));
			assertInvalid(new SignupRequest("id", "pw", "n", "0101234567", "WORKER", "DOMESTIC", "1111111111111",
				new SignupSalaryAccountRequest(null, "1", "n")));
		}

		private void assertInvalid(SignupRequest request) {
			IdentityCrypto spyCrypto = mock(IdentityCrypto.class);
			SalaryAccountCrypto spyAccount = mock(SalaryAccountCrypto.class);
			var guarded = new SignupService(members, accounts, passwords, spyCrypto, spyAccount, clock);
			assertThatThrownBy(() -> guarded.signup(request)).isInstanceOfSatisfying(SignupException.class,
				error -> assertThat(error.code()).isEqualTo("INVALID_REQUEST"));
			verifyNoInteractions(members, accounts, spyCrypto, spyAccount, passwords);
		}
	}

	@Nested
	@DisplayName("키 설정")
	class Keys {
		@Test
		@DisplayName("식별정보 키 미설정이면 IdentityCryptoUnavailableException, 조회·저장 없음 (계좌 생략·포함 모두)")
		void identityKeyMissing() {
			var broken = new SignupService(members, accounts, passwords, new IdentityCrypto("", ""), accountCrypto, clock);

			assertThatThrownBy(() -> broken.signup(request("WORKER", "DOMESTIC", null))).isInstanceOf(IdentityCryptoUnavailableException.class);
			assertThatThrownBy(() -> broken.signup(request("WORKER", "DOMESTIC", account()))).isInstanceOf(IdentityCryptoUnavailableException.class);
			verifyNoInteractions(members, accounts, passwords);
		}

		@Test
		@DisplayName("계좌 포함 가입만 계좌 키가 필요: 미설정이면 SalaryAccountCryptoUnavailableException, 조회·저장 없음")
		void accountKeyMissing() {
			var broken = new SignupService(members, accounts, passwords, identities, new SalaryAccountCrypto("bad"), clock);

			assertThatThrownBy(() -> broken.signup(request("WORKER", "DOMESTIC", account()))).isInstanceOf(SalaryAccountCryptoUnavailableException.class);
			verifyNoInteractions(members, accounts, passwords);
		}
	}

	@Nested
	@DisplayName("중복")
	class Duplicates {
		@Test
		@DisplayName("로그인 ID 중복 → 409 LOGIN_ID_ALREADY_EXISTS, 저장 없음")
		void loginId() {
			given(members.existsByLoginId("worker-01")).willReturn(true);

			assertConflict("LOGIN_ID_ALREADY_EXISTS");
		}

		@Test
		@DisplayName("HMAC 중복 → 409 IDENTITY_ALREADY_EXISTS (국적·역할이 달라도 같은 HMAC)")
		void hmac() {
			String hmac = identities.blindIndex(IDENTITY_NORMALIZED);
			given(members.existsByIdentityHmac(hmac)).willReturn(true);

			assertConflict("IDENTITY_ALREADY_EXISTS");
			for (String[] combo : new String[][] {{"EMPLOYER", "FOREIGN"}, {"WORKER", "FOREIGN"}, {"EMPLOYER", "DOMESTIC"}}) {
				assertThatThrownBy(() -> service.signup(request(combo[0], combo[1], null)))
					.isInstanceOfSatisfying(SignupException.class, error -> assertThat(error.code()).isEqualTo("IDENTITY_ALREADY_EXISTS"));
			}
			verify(members, org.mockito.Mockito.atLeast(4)).existsByIdentityHmac(hmac);
		}

		@Test
		@DisplayName("미이관 기존 번호 중복(정규화 번호로 조회) → 409 IDENTITY_ALREADY_EXISTS")
		void legacy() {
			given(members.countLegacyIdentityNumber(IDENTITY_NORMALIZED)).willReturn(1L);

			assertConflict("IDENTITY_ALREADY_EXISTS");
		}

		private void assertConflict(String code) {
			assertThatThrownBy(() -> service.signup(request("WORKER", "DOMESTIC", account())))
				.isInstanceOfSatisfying(SignupException.class, error -> {
					assertThat(error.status().value()).isEqualTo(409);
					assertThat(error.code()).isEqualTo(code);
				});
			verify(members, never()).saveAndFlush(any());
			verifyNoInteractions(accounts, passwords);
		}
	}

	@Nested
	@DisplayName("저장 중 예외는 성공으로 바뀌지 않고 전파")
	class Failures {
		@Test
		@DisplayName("회원 저장·계좌 저장·최종 flush 예외를 그대로 전파한다 (Java 객체 복원은 기대하지 않음)")
		void propagates() {
			org.mockito.Mockito.doThrow(new DataIntegrityViolationException("member")).when(members).saveAndFlush(any());
			assertThatThrownBy(() -> service.signup(request("WORKER", "DOMESTIC", account()))).isInstanceOf(DataIntegrityViolationException.class);
			verifyNoInteractions(accounts);

			org.mockito.Mockito.reset(members);
			setUp();
			org.mockito.Mockito.doThrow(new PersistenceException("account")).when(accounts).saveAndFlush(any());
			assertThatThrownBy(() -> service.signup(request("WORKER", "DOMESTIC", account()))).isInstanceOf(PersistenceException.class);

			org.mockito.Mockito.reset(accounts);
			org.mockito.Mockito.doThrow(new DataIntegrityViolationException("flush")).when(members).flush();
			assertThatThrownBy(() -> service.signup(request("WORKER", "DOMESTIC", null))).isInstanceOf(DataIntegrityViolationException.class);
		}

		@Test
		@DisplayName("signup 메서드에 기본 전파·롤백 규칙의 @Transactional이 선언돼 있다 (선언만 확인)")
		void transactional() throws Exception {
			Method signup = SignupService.class.getMethod("signup", SignupRequest.class);
			Transactional transactional = signup.getAnnotation(Transactional.class);

			assertThat(transactional).isNotNull();
			assertThat(transactional.readOnly()).isFalse();
			assertThat(transactional.propagation()).isEqualTo(org.springframework.transaction.annotation.Propagation.REQUIRED);
			assertThat(transactional.noRollbackFor()).isEmpty();
		}
	}

	private static SignupRequest withPassword(String password) {
		return new SignupRequest("id", password, "이름", "0101234567", "WORKER", "DOMESTIC", "1111111111111", null);
	}

	private static SignupRequest changed(String change) {
		String[] parts = change.split("=", 2);
		String value = parts[1];
		SignupSalaryAccountRequest account = account();
		UnaryOperator<SignupRequest> base = r -> r;
		return switch (parts[0]) {
			case "phone" -> new SignupRequest("id", "pw", "n", value, "WORKER", "DOMESTIC", "1111111111111", null);
			case "identity" -> new SignupRequest("id", "pw", "n", "0101234567", "WORKER", "DOMESTIC", value, null);
			case "role" -> new SignupRequest("id", "pw", "n", "0101234567", value, "DOMESTIC", "1111111111111", null);
			case "nationality" -> new SignupRequest("id", "pw", "n", "0101234567", "WORKER", value, "1111111111111", null);
			case "loginId" -> new SignupRequest(value, "pw", "n", "0101234567", "WORKER", "DOMESTIC", "1111111111111", null);
			case "name" -> new SignupRequest("id", "pw", value, "0101234567", "WORKER", "DOMESTIC", "1111111111111", null);
			case "account" -> {
				String[] item = value.split(":", 2);
				var changedAccount = switch (item[0]) {
					case "bank" -> new SignupSalaryAccountRequest(item[1], account.accountNumber(), account.accountHolderName());
					case "number" -> new SignupSalaryAccountRequest(account.bankCode(), item[1], account.accountHolderName());
					default -> new SignupSalaryAccountRequest(account.bankCode(), account.accountNumber(), item[1]);
				};
				yield base.apply(new SignupRequest("id", "pw", "n", "0101234567", "WORKER", "DOMESTIC", "1111111111111", changedAccount));
			}
			default -> throw new IllegalArgumentException(change);
		};
	}

	private Member savedMember() {
		ArgumentCaptor<Member> captor = ArgumentCaptor.forClass(Member.class);
		verify(members, org.mockito.Mockito.atLeastOnce()).saveAndFlush(captor.capture());
		return captor.getValue();
	}

	private static Object field(Member member, String name) {
		return ReflectionTestUtils.getField(member, name);
	}
}
