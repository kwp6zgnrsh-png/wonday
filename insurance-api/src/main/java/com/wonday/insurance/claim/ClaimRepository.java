package com.wonday.insurance.claim;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface ClaimRepository extends JpaRepository<ClaimCase, Long> {
	Optional<ClaimCase> findByContractIdAndAssignmentId(long contractId, long assignmentId);

	@Query("""
		select c.id as id, c.contractId as contractId, p.name as productName,
		  c.assignmentId as assignmentId, c.status as status, c.openedVia as openedVia,
		  c.openedAt as openedAt, c.updatedAt as updatedAt, c.workSnapshot as workSnapshot,
		  e.sourceChangeId as sourceChangeId, e.sourceVersion as sourceVersion,
		  e.eventType as eventType, e.reasonCode as reasonCode, e.reasonDetail as reasonDetail,
		  e.occurredAt as occurredAt
		from ClaimCase c join InsuranceContract i on i.id = c.contractId
		join ProductVersion v on v.id = i.productVersionId join v.product p
		join PlatformWorkEvent e on e.id = c.triggerEventId
		join PlatformLink l on l.id = e.platformLinkId
		where i.memberId = :memberId and l.memberId = :memberId
		  and (:claimId is null or c.id = :claimId)
		order by c.id desc
		""")
	List<ClaimRow> findOwned(long memberId, Long claimId);

	interface ClaimRow {
		long getId();
		long getContractId();
		String getProductName();
		long getAssignmentId();
		String getStatus();
		String getOpenedVia();
		LocalDateTime getOpenedAt();
		LocalDateTime getUpdatedAt();
		String getWorkSnapshot();
		long getSourceChangeId();
		String getSourceVersion();
		String getEventType();
		String getReasonCode();
		String getReasonDetail();
		LocalDateTime getOccurredAt();
	}
}
