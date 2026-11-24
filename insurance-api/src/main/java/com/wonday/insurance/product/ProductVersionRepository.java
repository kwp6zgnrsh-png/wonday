package com.wonday.insurance.product;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ProductVersionRepository extends JpaRepository<ProductVersion, Long> {

	@Query("""
		select version from ProductVersion version
		join fetch version.product product
		where product.status = 'ACTIVE'
		  and (:productId is null or product.id = :productId)
		  and version.salesStartAt <= :now
		  and (version.salesEndAt is null or version.salesEndAt > :now)
		  and not exists (
			select newer.id from ProductVersion newer
			where newer.product = product and newer.versionNo > version.versionNo
			  and newer.salesStartAt <= :now
			  and (newer.salesEndAt is null or newer.salesEndAt > :now)
		  )
		order by product.id
		""")
	List<ProductVersion> findAvailableVersions(@Param("productId") Long productId, @Param("now") LocalDateTime now);
}
