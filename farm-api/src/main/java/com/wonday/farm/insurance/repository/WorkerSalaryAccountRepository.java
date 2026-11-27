package com.wonday.farm.insurance.repository;

import java.util.Optional;

import com.wonday.farm.member.Member;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

// JPA의 읽기 전용 projection으로 필요한 컬럼만 가져온다. 계좌 암호문·예금주·식별정보는 조회하지 않는다.
public interface WorkerSalaryAccountRepository extends Repository<Member, Long> {
	// 한 번의 조회로 현재 회원 역할과 계좌 상태를 함께 읽는다. 계좌 미등록 회원도 LEFT JOIN으로 남긴다.
	@Query(value = """
		select m.id as workerId, m.role as memberRole,
		       account.member_id as accountMemberId, account.bank_code as bankCode
		from member m
		left join member_salary_account account on account.member_id = m.id
		where m.id = :workerId
		""", nativeQuery = true)
	Optional<AccountStatus> findStatus(@Param("workerId") long workerId);

	interface AccountStatus {
		long getWorkerId();
		String getMemberRole();
		Long getAccountMemberId();
		String getBankCode();
	}
}
