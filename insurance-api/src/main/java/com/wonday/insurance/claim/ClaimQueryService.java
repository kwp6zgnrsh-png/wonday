package com.wonday.insurance.claim;

import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class ClaimQueryService {
	private final ClaimRepository claims;
	private final ObjectMapper json;

	List<ClaimSummary> list(long memberId) {
		return claims.findOwned(memberId, null).stream().map(this::summary).toList();
	}

	ClaimDetail detail(long memberId, long claimId) {
		// 타인 기록과 없는 기록 모두 404로 응답해 다른 회원의 접수 존재 여부를 노출하지 않는다.
		ClaimRepository.ClaimRow row = claims.findOwned(memberId, claimId).stream().findFirst()
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Claim does not exist"));
		// 현재 농업 서버를 다시 조회하지 않고 최초 접수에 연결된 이벤트와 약정 스냅샷을 반환한다.
		return new ClaimDetail(summary(row), new IntakeSource(row.getSourceChangeId(), row.getSourceVersion(),
			row.getEventType(), row.getReasonCode(), row.getReasonDetail(), row.getOccurredAt()),
			json.readTree(row.getWorkSnapshot()));
	}

	private ClaimSummary summary(ClaimRepository.ClaimRow row) {
		return new ClaimSummary(row.getId(), row.getContractId(), row.getProductName(), row.getAssignmentId(),
			row.getStatus(), row.getOpenedVia(), row.getOpenedAt(), row.getUpdatedAt());
	}

	record ClaimSummary(long id, long contractId, String productName, long assignmentId, String status,
		String openedVia, LocalDateTime openedAt, LocalDateTime updatedAt) {}

	record IntakeSource(long changeId, String sourceVersion, String changeType, String reasonCode,
		String reasonDetail, LocalDateTime occurredAt) {}

	record ClaimDetail(ClaimSummary claim, IntakeSource intakeSource, JsonNode work) {}
}
