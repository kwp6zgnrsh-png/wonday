package com.wonday.farm.demo.assignment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.wonday.farm.work.entity.WorkAssignment;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

// 화면에서 본인 약정 ID를 찾기 위한 읽기 전용 목록이다. 데모 프로필 이외에는 등록하지 않는다.
@Profile("farm-demo")
public interface DemoWorkAssignmentRepository extends Repository<WorkAssignment, Long> {
	@Query(value = """
		select assignment.id as assignmentId, assignment.agreed_work_date as agreedWorkDate,
		       assignment.agreed_work_location as agreedWorkLocation,
		       assignment.agreed_latitude as agreedLatitude, assignment.agreed_longitude as agreedLongitude
		from work_assignment assignment
		join application app on app.id = assignment.application_id
		where app.worker_id = :workerId
		order by assignment.id desc
		limit 100
		""", nativeQuery = true)
	List<Option> findMine(@Param("workerId") long workerId);

	interface Option {
		long getAssignmentId();
		LocalDate getAgreedWorkDate();
		String getAgreedWorkLocation();
		BigDecimal getAgreedLatitude();
		BigDecimal getAgreedLongitude();
	}
}
