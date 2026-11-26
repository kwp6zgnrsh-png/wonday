package com.wonday.farm.insurance.repository;

import java.util.List;

import com.wonday.farm.member.Member;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface WorkerVerificationRepository extends Repository<Member, Long> {
	// 이관 전 호환 모드 전용이다. 명시된 외국인은 제외하고 기존 원문 컬럼은 읽기만 한다.
	@Query(value = """
		select m.id as workerId,
		       regexp_replace(m.name, '^[[:space:]]+|[[:space:]]+$', '') as name,
		       regexp_replace(m.phone, '[-[:space:]]', '') as phone
		from member m
		where cast(m.role as binary) = cast('WORKER' as binary)
		  and (m.nationality_type is null or cast(m.nationality_type as binary) = cast('DOMESTIC' as binary))
		  and (m.identity_type is null or cast(m.identity_type as binary) = cast('RESIDENT_REGISTRATION_NUMBER' as binary))
		  and cast(regexp_replace(m.name, '^[[:space:]]+|[[:space:]]+$', '') as binary) = cast(:name as binary)
		  and regexp_replace(m.phone, '[-[:space:]]', '') = :phone
		  and regexp_replace(m.resident_registration_number, '[-[:space:]]', '') = :residentNumber
		order by m.id
		limit 2
		""", nativeQuery = true)
	List<Match> findMatches(@Param("name") String name, @Param("phone") String phone,
		@Param("residentNumber") String residentNumber);

	// 전환 완료 모드에서는 원문 컬럼을 조회하지 않고 정규화 번호의 HMAC으로 대조한다.
	@Query(value = """
		select m.id as workerId,
		       regexp_replace(m.name, '^[[:space:]]+|[[:space:]]+$', '') as name,
		       regexp_replace(m.phone, '[-[:space:]]', '') as phone
		from member m
		where cast(m.role as binary) = cast('WORKER' as binary)
		  and cast(m.nationality_type as binary) = cast('DOMESTIC' as binary)
		  and cast(m.identity_type as binary) = cast('RESIDENT_REGISTRATION_NUMBER' as binary)
		  and m.identity_ciphertext is not null
		  and m.identity_hmac = :identityHmac
		  and cast(regexp_replace(m.name, '^[[:space:]]+|[[:space:]]+$', '') as binary) = cast(:name as binary)
		  and regexp_replace(m.phone, '[-[:space:]]', '') = :phone
		order by m.id
		limit 2
		""", nativeQuery = true)
	List<Match> findDomesticByIdentityHmac(@Param("name") String name, @Param("phone") String phone,
		@Param("identityHmac") String identityHmac);

	interface Match {
		long getWorkerId();
		String getName();
		String getPhone();
	}
}
