package com.wonday.farm.member;

import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// Spring Data JPA가 메서드 이름으로 로그인 ID 조회와 존재 확인 쿼리를 만든다.
public interface MemberRepository extends JpaRepository<Member, Long> {

	// 계좌 미등록 상태에서도 잠글 수 있는 회원 행을 기준으로 같은 회원의 변경을 직렬화한다.
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select m from Member m where m.id = :memberId")
	Optional<Member> findByIdForSalaryAccountUpdate(@Param("memberId") long memberId);

	Optional<Member> findByLoginId(String loginId);

	boolean existsByLoginId(String loginId);

	boolean existsByIdentityHmac(String identityHmac);

	// 이관 전 번호도 확인하되 새 가입자의 번호를 기존 원문 컬럼에 저장하지 않는다.
	@Query(value = """
		select count(*) from member
		where regexp_replace(resident_registration_number, '[-[:space:]]', '') = :identityNumber
		""", nativeQuery = true)
	long countLegacyIdentityNumber(@Param("identityNumber") String identityNumber);
}
