package com.wonday.insurance.claim;

import com.wonday.insurance.contract.ContractClaimCatalog;
import com.wonday.insurance.platform.PlatformClaimEvents;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 저장된 변경 기록 하나를 판정하고 접수·최초 이력·이벤트 처리 결과를 함께 저장한다. */
@Service
@RequiredArgsConstructor
class ClaimIntakeService {
	private final PlatformClaimEvents events;
	private final ContractClaimCatalog contracts;
	private final ClaimRepository claims;
	private final ClaimHistoryRepository history;
	private final Clock clock;

	@Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
	public String process(long eventId) {
		// 이벤트별 독립 트랜잭션이다. 업무 조건 미충족은 제외로 완료하고 시스템 오류는 예외로 롤백한다.
		PlatformClaimEvents.Event event = events.lockPending(eventId);
		if (event == null) return "ALREADY_PROCESSED";
		if (!event.activeLink()) return finish(eventId, "INACTIVE_LINK");
		if (!"CANCELLED".equals(event.type())) return finish(eventId, "NOT_CANCELLED");
		if (!"WEATHER".equals(event.reasonCode())) return finish(eventId, "NON_WEATHER_REASON");

		PlatformClaimEvents.Work work = events.verifiedWork(event);
		// '당일'은 약정 작업지의 현지 날짜로 판단한다. 신규 업무 시간대 표기는 GMT+9를 사용한다.
		// 시작 전 여부는 양쪽 모두 UTC로 비교하므로 오프셋이나 서버 기본 시간대에 영향을 받지 않는다.
		boolean sameDay = event.occurredAt().atOffset(ZoneOffset.UTC).atZoneSameInstant(ZoneId.of(work.timeZone()))
			.toLocalDate().equals(work.workDate());
		if (!sameDay || !event.occurredAt().isBefore(work.startAt())) {
			return finish(eventId, "NOT_SAME_DAY_BEFORE_START");
		}
		List<Long> contractIds = contracts.lockCoveredContractIds(event.memberId(), event.occurredAt());
		if (contractIds.isEmpty()) return finish(eventId, "NO_COVERED_CONTRACT");

		LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
		int created = 0;
		for (long contractId : contractIds) {
			// 계약 잠금으로 동시 생성을 직렬화한다. 후속 버전이 와도 기존 심사·지급 상태와 최초 근거는 유지한다.
			if (claims.findByContractIdAndAssignmentId(contractId, event.assignmentId()).isPresent()) continue;
			ClaimCase claim = claims.saveAndFlush(new ClaimCase(contractId, event.assignmentId(), eventId, work.snapshot(), now));
			// flush는 커밋이 아니다. 이력 저장이 실패하면 앞서 저장한 접수도 함께 롤백된다.
			history.saveAndFlush(new ClaimHistory(claim.getId(), now));
			created++;
		}
		return finish(eventId, created > 0 ? "RECEIVED" : "ALREADY_RECEIVED");
	}

	private String finish(long eventId, String result) {
		events.complete(eventId, result);
		return result;
	}
}
