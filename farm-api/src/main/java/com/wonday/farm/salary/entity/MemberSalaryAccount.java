package com.wonday.farm.salary.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// 회원 ID가 PK이므로 한 회원에게 계좌 한 개만 저장된다. 원문 번호와 표시용 하이픈은 저장하지 않는다.
@Entity
@Table(name = "member_salary_account")
public class MemberSalaryAccount {
	@Id
	@Column(name = "member_id")
	private Long memberId;
	@Column(name = "bank_code", nullable = false, length = 3, columnDefinition = "char(3)")
	private String bankCode;
	@Column(name = "account_number_ciphertext", nullable = false, length = 256)
	private String accountNumberCiphertext;
	@Column(name = "account_holder_name", nullable = false, length = 100)
	private String accountHolderName;
	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;
	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	protected MemberSalaryAccount() {}

	public MemberSalaryAccount(long memberId, String bankCode, String ciphertext, String holder, LocalDateTime nowUtc) {
		validate(memberId, bankCode, ciphertext, holder, nowUtc);
		this.memberId = memberId;
		this.bankCode = bankCode;
		this.accountNumberCiphertext = ciphertext;
		this.accountHolderName = holder;
		this.createdAt = nowUtc;
		this.updatedAt = nowUtc;
	}

	private static void validate(long memberId, String bankCode, String ciphertext, String holder, LocalDateTime nowUtc) {
		if (memberId <= 0 || bankCode == null || !bankCode.matches("[0-9]{3}") || ciphertext == null
				|| !ciphertext.startsWith("v1:") || ciphertext.length() <= 3 || ciphertext.length() > 256
				|| holder == null || holder.isBlank() || holder.length() > 100 || nowUtc == null) {
			throw new IllegalArgumentException("급여 계좌 저장값을 확인해 주세요.");
		}
	}

	// 조회 API로 엔티티를 반환하지 않는다. 암호문은 서버 내부 마스킹 처리에서만 사용한다.
	public String ciphertextForDecryption() { return accountNumberCiphertext; }

	public void replace(String bankCode, String ciphertext, String holder, LocalDateTime nowUtc) {
		validate(memberId, bankCode, ciphertext, holder, nowUtc);
		this.bankCode = bankCode;
		this.accountNumberCiphertext = ciphertext;
		this.accountHolderName = holder;
		this.updatedAt = nowUtc;
	}


	public Long getMemberId() { return memberId; }
	public String bankCode() { return bankCode; }
	public String accountHolderName() { return accountHolderName; }
	public LocalDateTime createdAt() { return createdAt; }
	public LocalDateTime updatedAt() { return updatedAt; }
	@Override public String toString() { return "MemberSalaryAccount[redacted]"; }
}
