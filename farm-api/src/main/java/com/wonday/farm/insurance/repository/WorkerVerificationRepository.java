package com.wonday.farm.insurance.repository;

import java.util.List;

import com.wonday.farm.member.Member;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface WorkerVerificationRepository extends Repository<Member, Long> {
	// 저장된 시연 데이터에도 같은 정규화를 적용한다. 두 건까지만 읽어 중복을 판단하고 주민번호는 반환하지 않는다.
	@Query(value = """
		select m.id as workerId,
		       regexp_replace(m.name, '^[[:space:]]+|[[:space:]]+$', '') as name,
		       regexp_replace(m.phone, '[-[:space:]]', '') as phone
		from member m
		where cast(m.role as binary) = cast('WORKER' as binary)
		  and cast(regexp_replace(m.name, '^[[:space:]]+|[[:space:]]+$', '') as binary) = cast(:name as binary)
		  and regexp_replace(m.phone, '[-[:space:]]', '') = :phone
		  and regexp_replace(m.resident_registration_number, '[-[:space:]]', '') = :residentNumber
		order by m.id
		limit 2
		""", nativeQuery = true)
	List<Match> findMatches(@Param("name") String name, @Param("phone") String phone,
		@Param("residentNumber") String residentNumber);

	interface Match {
		long getWorkerId();
		String getName();
		String getPhone();
	}
}
