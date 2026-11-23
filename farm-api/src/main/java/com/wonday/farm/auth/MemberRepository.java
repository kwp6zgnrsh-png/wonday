package com.wonday.farm.auth;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

// Spring Data JPA가 메서드 이름으로 로그인 ID 조회와 존재 확인 쿼리를 만든다.
public interface MemberRepository extends JpaRepository<Member, Long> {
	Optional<Member> findByLoginId(String loginId);

	boolean existsByLoginId(String loginId);
}
