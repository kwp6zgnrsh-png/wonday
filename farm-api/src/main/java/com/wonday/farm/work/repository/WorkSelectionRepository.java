package com.wonday.farm.work.repository;

import java.util.Optional;
import com.wonday.farm.work.entity.WorkAssignment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkSelectionRepository extends JpaRepository<WorkAssignment, Long> {
    // 공고·일정·지원 잠금 뒤에 확정 근무 → 변경 순서로 읽어 완료/취소와 잠금 순서를 맞춘다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select assignment from WorkAssignment assignment where assignment.applicationId = :applicationId")
    Optional<WorkAssignment> findByApplicationForUpdate(@Param("applicationId") long applicationId);
}
