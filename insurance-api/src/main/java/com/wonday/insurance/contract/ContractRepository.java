package com.wonday.insurance.contract;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ContractRepository extends JpaRepository<InsuranceContract, Long> {

	@Query("""
		select count(c) > 0 from InsuranceContract c
		join ProductVersion v on v.id = c.productVersionId
		where c.memberId = :memberId and v.product.id = :productId
		  and c.status = 'ACTIVE' and c.coverageTo > :now
		""")
	boolean hasCurrentContract(@Param("memberId") long memberId, @Param("productId") long productId,
		@Param("now") LocalDateTime now);

	@Query("""
		select new com.wonday.insurance.contract.ContractView(
			c.id, c.productVersionId, p.name, v.versionNo, c.acceptedAt,
			c.coverageFrom, c.coverageTo,
			case when c.status = 'ACTIVE' and c.coverageTo <= :now then 'EXPIRED'
			when c.status = 'ACTIVE' and c.coverageFrom > :now then 'PENDING' else c.status end, v.termsText)
		from InsuranceContract c join ProductVersion v on v.id = c.productVersionId
		join v.product p
		where c.memberId = :memberId and (:contractId is null or c.id = :contractId)
		order by c.id desc
		""")
	List<ContractView> findOwned(@Param("memberId") long memberId, @Param("contractId") Long contractId,
		@Param("now") LocalDateTime now);
}
