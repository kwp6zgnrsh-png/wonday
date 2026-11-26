package com.wonday.farm.salary.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import com.wonday.farm.member.Member;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import com.wonday.farm.salary.crypto.SalaryAccountCrypto;
import com.wonday.farm.salary.dto.SalaryAccountRequest;
import com.wonday.farm.salary.dto.SalaryAccountResponse;
import com.wonday.farm.salary.entity.MemberSalaryAccount;
import com.wonday.farm.salary.exception.SalaryAccountException;
import com.wonday.farm.salary.repository.MemberSalaryAccountRepository;
import com.wonday.farm.salary.validation.SalaryAccountValidator;
import com.wonday.farm.salary.validation.SalaryAccountValidator.NormalizedAccount;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SalaryAccountService {
	private final MemberRepository members;
	private final MemberSalaryAccountRepository accounts;
	private final SalaryAccountCrypto crypto;
	private final Clock clock;

	public SalaryAccountService(MemberRepository members, MemberSalaryAccountRepository accounts,
			SalaryAccountCrypto crypto, @Qualifier("jobClock") Clock clock) {
		this.members = members;
		this.accounts = accounts;
		this.crypto = crypto;
		this.clock = clock;
	}

	// 회원 버전과 계좌를 별도 SELECT로 읽으므로 같은 스냅샷을 사용한다.
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public SalaryAccountResponse findMine(long memberId) {
		Member member = worker(memberId, false);
		return accounts.findById(memberId).map(account -> response(member, account))
			.orElseGet(() -> new SalaryAccountResponse(false, null, null, null, member.salaryAccountVersion()));
	}

	@Transactional
	public SalaryAccountResponse replace(long memberId, SalaryAccountRequest request) {
		NormalizedAccount input = validate(request);
		// 계좌가 없어도 존재하는 회원 행을 잠근다. 같은 회원의 최초 등록·교체·해제를 직렬화한다.
		Member member = worker(memberId, true);
		requireVersion(member, request.version());
		crypto.requireConfigured();
		LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
		String ciphertext = crypto.encrypt(memberId, input.bankCode(), input.accountNumber());
		var existing = accounts.findById(memberId);
		MemberSalaryAccount account;
		if (existing.isPresent()) {
			account = existing.get();
			account.replace(input.bankCode(), ciphertext, input.accountHolderName(), now);
		} else {
			account = new MemberSalaryAccount(memberId, input.bankCode(), ciphertext, input.accountHolderName(), now);
		}
		member.advanceSalaryAccountVersion();
		MemberSalaryAccount saved = accounts.saveAndFlush(account);
		members.flush();
		return response(member, saved);
	}

	@Transactional
	public void remove(long memberId, long expectedVersion) {
		if (expectedVersion < 0) throw SalaryAccountException.invalid();
		Member member = worker(memberId, true);
		var account = accounts.findById(memberId);
		// 유효한 요청에서 현재 미등록이면 오래된 버전이어도 204다. 재등록된 계좌는 버전을 검사한다.
		if (account.isEmpty()) return;
		requireVersion(member, expectedVersion);
		accounts.delete(account.get());
		member.advanceSalaryAccountVersion();
		accounts.flush();
		members.flush();
	}

	private Member worker(long memberId, boolean lock) {
		if (memberId <= 0) throw SalaryAccountException.forbidden();
		Member member = (lock ? members.findByIdForSalaryAccountUpdate(memberId) : members.findById(memberId))
			.orElseThrow(SalaryAccountException::forbidden);
		// 로그인 이후 DB 역할이 바뀌거나 삭제된 계정도 거절한다.
		if (member.memberRole().orElse(null) != MemberRole.WORKER) throw SalaryAccountException.forbidden();
		return member;
	}

	private NormalizedAccount validate(SalaryAccountRequest request) {
		if (request == null || request.version() == null || request.version() < 0) throw SalaryAccountException.invalid();
		try {
			return SalaryAccountValidator.normalize(request.bankCode(), request.accountNumber(), request.accountHolderName());
		} catch (IllegalArgumentException exception) {
			throw SalaryAccountException.invalid();
		}
	}

	private void requireVersion(Member member, long expected) {
		if (member.salaryAccountVersion() != expected) throw SalaryAccountException.conflict();
		if (member.salaryAccountVersion() == Long.MAX_VALUE) throw SalaryAccountException.unavailable();
	}

	private SalaryAccountResponse response(Member member, MemberSalaryAccount account) {
		String masked = crypto.maskedAccountNumber(member.getId(), account.bankCode(), account.ciphertextForDecryption());
		return new SalaryAccountResponse(true, account.bankCode(), masked, account.accountHolderName(), member.salaryAccountVersion());
	}
}
