package com.wonday.farm.job.repository;

import com.wonday.farm.job.entity.WorkScheduleRevision;
import org.springframework.data.repository.Repository;

// 수정 이력은 추가만 한다. 변경·삭제 API는 노출하지 않는다.
public interface WorkScheduleRevisionRepository extends Repository<WorkScheduleRevision, Long> {
	boolean existsByWorkSchedule_IdAndTermsVersion(long workScheduleId, int termsVersion);

	WorkScheduleRevision save(WorkScheduleRevision revision);
}
