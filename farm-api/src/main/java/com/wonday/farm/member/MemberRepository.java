package com.wonday.farm.member;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// Spring Data JPA가 메서드 이름으로 로그인 ID 조회와 존재 확인 쿼리를 만든다.
public interface MemberRepository extends JpaRepository<Member, Long> {
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
