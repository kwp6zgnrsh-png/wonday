package com.wonday.insurance.claim;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** 관리자 조회 전용 저장소다. 회원 식별번호 컬럼이나 엔티티 전체를 조회하지 않는다. */
interface AdminClaimRepository extends Repository<ClaimCase, Long> {
	String SUMMARY = """
		select c.id as id, i.memberId as memberId, m.name as memberName,
		c.contractId as contractId, p.name as productName, v.versionNo as productVersionNo,
		c.assignmentId as assignmentId, c.status as status, c.openedVia as openedVia,
		c.openedAt as openedAt, c.updatedAt as updatedAt
		""";
	String RELATIONS = """
		from ClaimCase c join InsuranceContract i on i.id=c.contractId
		join Member m on m.id=i.memberId
		join ProductVersion v on v.id=i.productVersionId join v.product p
		join PlatformWorkEvent e on e.id=c.triggerEventId
		join PlatformLink l on l.id=e.platformLinkId
		where l.memberId=i.memberId and e.assignmentId=c.assignmentId
		""";

	// 목록에는 무거운 약정/이벤트 JSON을 싣지 않고 필요한 열만 읽는다. 철회·만료된 계약도 과거 기록으로 조회한다.
	@Query(SUMMARY + RELATIONS + """
		and (:status is null or c.status=:status) and (:memberId is null or i.memberId=:memberId)
		and (:beforeId is null or c.id<:beforeId) order by c.id desc
		""")
	List<SummaryRow> list(@Param("status") String status, @Param("memberId") Long memberId,
		@Param("beforeId") Long beforeId, Pageable page);

	@Query(SUMMARY + """
		, i.productVersionId as productVersionId, p.productCode as productCode, v.termsText as termsText,
		i.acceptedAt as acceptedAt, i.coverageFrom as coverageFrom, i.coverageTo as coverageTo, i.status as contractStatus,
		e.id as eventId, e.sourceChangeId as changeId, e.sourceVersion as sourceVersion,
		e.eventType as changeType, e.reasonCode as reasonCode, e.reasonDetail as reasonDetail,
		e.occurredAt as occurredAt, e.sourceUpdatedAt as sourceUpdatedAt, e.receivedAt as receivedAt,
		e.sourcePayload as sourcePayload, l.workerId as workerId, c.workSnapshot as workSnapshot
		""" + RELATIONS + " and c.id=:claimId")
	Optional<DetailRow> detail(@Param("claimId") long claimId);

	@Query("select c.id " + RELATIONS + " and c.id=:claimId")
	Optional<Long> visibleId(@Param("claimId") long claimId);

	@Query("""
		select h.id as id, h.action as action, h.fromStatus as fromStatus, h.toStatus as toStatus,
		h.reason as reason, h.actorId as actorId, h.assessmentId as assessmentId, h.createdAt as createdAt
		from ClaimHistory h where h.claimCaseId=:claimId and (:beforeId is null or h.id<:beforeId)
		order by h.id desc
		""")
	List<HistoryRow> history(@Param("claimId") long claimId, @Param("beforeId") Long beforeId, Pageable page);

	interface SummaryRow {
		long getId(); long getMemberId(); String getMemberName(); long getContractId(); String getProductName();
		int getProductVersionNo(); long getAssignmentId(); String getStatus(); String getOpenedVia();
		LocalDateTime getOpenedAt(); LocalDateTime getUpdatedAt();
	}
	interface DetailRow extends SummaryRow {
		long getProductVersionId(); String getProductCode(); String getTermsText(); String getContractStatus();
		LocalDateTime getAcceptedAt(); LocalDateTime getCoverageFrom(); LocalDateTime getCoverageTo();
		long getEventId(); long getChangeId(); String getSourceVersion(); String getChangeType();
		String getReasonCode(); String getReasonDetail(); LocalDateTime getOccurredAt();
		LocalDateTime getSourceUpdatedAt(); LocalDateTime getReceivedAt(); String getSourcePayload();
		Long getWorkerId(); String getWorkSnapshot();
	}
	interface HistoryRow {
		long getId(); String getAction(); String getFromStatus(); String getToStatus(); String getReason();
		Long getActorId(); Long getAssessmentId(); LocalDateTime getCreatedAt();
	}
}
