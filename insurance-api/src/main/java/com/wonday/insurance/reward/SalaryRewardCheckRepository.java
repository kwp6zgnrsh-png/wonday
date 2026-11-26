package com.wonday.insurance.reward;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SalaryRewardCheckRepository extends JpaRepository<SalaryRewardCheck, Long> {

	Optional<SalaryRewardCheck> findByMemberIdAndRequestKey(long memberId, String requestKey);
	Optional<SalaryRewardCheck> findFirstByMemberIdOrderByIdDesc(long memberId);

	@Query("select c from SalaryRewardCheck c where c.memberId = :memberId and (:beforeId is null or c.id < :beforeId) order by c.id desc")
	List<SalaryRewardCheck> history(@Param("memberId") long memberId, @Param("beforeId") Long beforeId, Pageable page);
}
