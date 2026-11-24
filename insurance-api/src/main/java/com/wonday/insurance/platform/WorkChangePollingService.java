package com.wonday.insurance.platform;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** 농업 변경 기록과 확정 근무를 한 페이지씩 저장한다. 기록 저장과 커서 이동은 함께 성공해야 한다. */
@Service
@RequiredArgsConstructor
class WorkChangePollingService {

	private final PlatformLinkRepository links;
	private final PlatformWorkCursorRepository cursors;
	private final PlatformWorkEventRepository events;
	private final FarmWorkClient farm;
	private final ObjectMapper json;
	private final Clock clock;
	private final WorkPollingProperties properties;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean pollPage(long linkId) {
		// 유효한 연동 행을 잠가 동시 수집을 직렬화하고, 철회된 연동의 조회는 중단한다.
		PlatformLink link = links.lockPollingLink(linkId).orElse(null);
		if (link == null) return false;
		long workerId = link.getWorkerId();
		LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
		PlatformWorkCursor cursor = cursors.findById(workerId)
			.orElseGet(() -> new PlatformWorkCursor(workerId, now));
		// 커서는 연동 행이 아닌 근로자에 귀속시켜 동의를 갱신해도 수집 위치를 이어간다.
		FarmWorkResponse.Page page = farm.changes(workerId, cursor.getCursor(), properties.pageSize());
		for (FarmWorkResponse.Change change : page.items()) {
			PlatformWorkEvent existing = events.findBySourceChangeIdAndSourceVersion(
				change.changeId(), change.sourceVersion()).orElse(null);
			if (existing != null) {
				verifyDuplicate(existing, change, workerId);
				continue;
			}
			FarmWorkResponse.Assignment assignment = farm.assignment(workerId, change.assignmentId());
			// 이후 공고가 바뀌어도 접수 근거를 재현할 수 있도록 수신 당시의 약정 상세를 보관한다.
			events.saveAndFlush(new PlatformWorkEvent(linkId, change, json.writeValueAsString(change),
				json.writeValueAsString(assignment), now));
		}
		// 상세 조회나 저장이 실패하면 페이지 전체가 롤백된다. 커서만 앞서가 기록을 놓치지 않게 한다.
		cursor.advance(page.nextCursor(), now);
		cursors.saveAndFlush(cursor);
		return page.hasMore();
	}

	private void verifyDuplicate(PlatformWorkEvent existing, FarmWorkResponse.Change change, long workerId) {
		PlatformLink owner = links.findById(existing.getPlatformLinkId()).orElseThrow();
		// 같은 ID·버전의 다른 내용은 정상 중복이 아니다. JSON이 없는 이전 데이터도 저장 컬럼으로 비교한다.
		boolean contentMatches = existing.getSourcePayload() == null
			? existing.matchesStoredFields(change)
			: json.readTree(existing.getSourcePayload()).equals(json.readTree(json.writeValueAsString(change)));
		if (!Long.valueOf(workerId).equals(owner.getWorkerId()) || existing.getAssignmentId() != change.assignmentId()
			|| !contentMatches) {
			throw FarmWorkClient.invalidResponse();
		}
	}
}
