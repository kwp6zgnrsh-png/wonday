package com.wonday.insurance.member;

import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

interface MemberRepository extends JpaRepository<Member, Long> {

	Optional<Member> findByLoginId(String loginId);

	// 원격 호출 전 사전 검사는 엔티티를 영속성 컨텍스트에 남기지 않는다. 저장 시 잠금 조회로 최신 상태를 읽는다.
	@Query("select m.role as role, m.nationalityType as nationalityType from Member m where m.id = :id")
	Optional<EnrollmentProfile> findEnrollmentProfile(@Param("id") long id);

	interface EnrollmentProfile {
		MemberRole getRole();
		NationalityType getNationalityType();
	}

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select m from Member m where m.id = :id")
	Optional<Member> findForEnrollment(@Param("id") long id);
}
