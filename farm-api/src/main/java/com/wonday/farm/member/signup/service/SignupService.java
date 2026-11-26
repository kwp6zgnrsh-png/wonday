package com.wonday.farm.member.signup.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import com.wonday.farm.auth.PasswordPolicy;
import com.wonday.farm.member.Member;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import com.wonday.farm.member.identity.IdentityCrypto;
import com.wonday.farm.member.identity.IdentityNumber;
import com.wonday.farm.member.identity.NationalityType;
import com.wonday.farm.member.signup.dto.SignupRequest;
import com.wonday.farm.member.signup.dto.SignupResponse;
import com.wonday.farm.member.signup.exception.SignupException;
import com.wonday.farm.salary.crypto.SalaryAccountCrypto;
import com.wonday.farm.salary.entity.MemberSalaryAccount;
import com.wonday.farm.salary.repository.MemberSalaryAccountRepository;
import com.wonday.farm.salary.validation.SalaryAccountValidator;
import com.wonday.farm.salary.validation.SalaryAccountValidator.NormalizedAccount;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SignupService {
	private final MemberRepository members;
	private final MemberSalaryAccountRepository accounts;
	private final PasswordEncoder passwords;
	private final IdentityCrypto identities;
	private final SalaryAccountCrypto accountCrypto;
	private final Clock clock;

	public SignupService(MemberRepository members, MemberSalaryAccountRepository accounts, PasswordEncoder passwords,
			IdentityCrypto identities, SalaryAccountCrypto accountCrypto, @Qualifier("jobClock") Clock clock) {
		this.members = members;
		this.accounts = accounts;
		this.passwords = passwords;
		this.identities = identities;
		this.accountCrypto = accountCrypto;
		this.clock = clock;
	}

	// ID 발급·식별정보 연결·선택 계좌 저장 중 하나라도 실패하면 이번 가입 전체를 롤백한다.
	@Transactional
	public SignupResponse signup(SignupRequest request) {
		if (request == null) throw SignupException.invalid();
		String loginId = required(request.loginId(), 100).trim();
		String password = required(request.password(), 72);
		String name = required(request.name(), 100).strip();
		String phone = required(request.phone(), 100).replaceAll("(?U)[\\s-]", "");
		if (loginId.isBlank() || name.isBlank() || !phone.matches("[0-9]{10,11}")
				|| PasswordPolicy.exceedsBcryptLimit(password)) throw SignupException.invalid();
		MemberRole role;
		NationalityType nationality;
		String identityNumber;
		NormalizedAccount account = null;
		try {
			role = MemberRole.valueOf(required(request.role(), 32));
			nationality = NationalityType.valueOf(required(request.nationalityType(), 16));
			identityNumber = IdentityNumber.normalize(request.identityNumber());
			if (request.salaryAccount() != null) {
				var input = request.salaryAccount();
				account = SalaryAccountValidator.normalize(input.bankCode(), input.accountNumber(), input.accountHolderName());
			}
		} catch (IllegalArgumentException exception) {
			throw SignupException.invalid();
		}
		// 필수 키를 확인한 뒤에만 DB에 회원을 만든다. 미설정이어도 기존 로그인은 그대로 동작한다.
		identities.requireConfigured();
		if (account != null) accountCrypto.requireConfigured();
		String hmac = identities.blindIndex(identityNumber);
		if (members.existsByLoginId(loginId)) throw SignupException.duplicateLogin();
		if (members.existsByIdentityHmac(hmac) || members.countLegacyIdentityNumber(identityNumber) > 0) {
			throw SignupException.duplicateIdentity();
		}
		// ID가 암호문의 AAD에 필요하다. 이 최초 INSERT도 같은 트랜잭션에 속한다.
		Member member = members.saveAndFlush(new Member(loginId, passwords.encode(password), name, phone, role));
		member.assignIdentity(identities.protect(member.getId(), nationality, identityNumber));
		if (account != null) {
			String ciphertext = accountCrypto.encrypt(member.getId(), account.bankCode(), account.accountNumber());
			LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
			accounts.saveAndFlush(new MemberSalaryAccount(member.getId(), account.bankCode(), ciphertext, account.accountHolderName(), now));
		}
		// 사전 중복 조회 뒤의 동시 가입도 DB UNIQUE로 거절한다. 예외는 트랜잭션 밖에서 응답으로 변환한다.
		members.flush();
		return new SignupResponse(member.getId(), loginId, name, role, nationality);
	}

	private static String required(String value, int maximum) {
		if (value == null || value.isBlank() || value.length() > maximum) throw SignupException.invalid();
		return value;
	}
}
