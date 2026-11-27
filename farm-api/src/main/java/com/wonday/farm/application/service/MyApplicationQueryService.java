package com.wonday.farm.application.service;

import java.util.List;
import java.util.Set;
import com.wonday.farm.application.dto.MyApplicationResponses;
import com.wonday.farm.application.exception.MyApplicationQueryException;
import com.wonday.farm.application.repository.MyApplicationQueryRepository;
import com.wonday.farm.application.repository.MyApplicationQueryRepository.Row;
import com.wonday.farm.application.validation.ApplicationHistoryCursor;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class MyApplicationQueryService {
	private static final Set<String> FILTERS = Set.of("ALL", "PENDING", "CONFIRMED", "NOT_SELECTED",
		"CANCELLED", "EARLY_ENDED", "COMPLETED");
	private final MemberRepository members;
	private final MyApplicationQueryRepository repository;
	private final ApplicationConditionMapper mapper;

	public MyApplicationQueryService(MemberRepository members, MyApplicationQueryRepository repository,
			ApplicationConditionMapper mapper) {
		this.members = members; this.repository = repository; this.mapper = mapper;
	}

	public MyApplicationResponses.Page list(long workerId, String status, String limit, String cursor) {
		requireWorker(workerId);
		String filter = status == null ? "ALL" : status;
		if (!FILTERS.contains(filter)) throw MyApplicationQueryException.invalidRequest();
		int size = pageSize(limit);
		Long anchorId = ApplicationHistoryCursor.decode(cursor, workerId, "LIST", 0, filter);
		Row anchor = anchorId == null ? null : cursorAnchor(workerId, anchorId);
		// 앵커가 조회 후 과거 회차가 됐어도 원래 경계 시각은 유지해 다음 페이지를 이어 읽는다.
		var rows = repository.findCurrent(workerId, filter, anchor == null ? null : anchor.getSortAppliedAt(),
			anchorId, size + 1);
		boolean hasMore = rows.size() > size;
		var visible = rows.subList(0, Math.min(size, rows.size()));
		String next = hasMore ? ApplicationHistoryCursor.encode(workerId, "LIST", 0, filter,
			visible.getLast().getApplicationId()) : null;
		return new MyApplicationResponses.Page(visible.stream().map(mapper::item).toList(), next, hasMore);
	}

	public MyApplicationResponses.Detail detail(long workerId, long applicationId) {
		requireWorker(workerId);
		return mapper.detail(owned(workerId, applicationId));
	}

	public MyApplicationResponses.History history(long workerId, long applicationId, String limit, String cursor) {
		requireWorker(workerId);
		Row reference = owned(workerId, applicationId);
		long scheduleId = reference.getWorkScheduleId();
		int size = pageSize(limit);
		Long anchorId = ApplicationHistoryCursor.decode(cursor, workerId, "HISTORY", scheduleId, "ALL");
		Row anchor = anchorId == null ? null : cursorAnchor(workerId, anchorId);
		if (anchor != null && (anchor.getWorkScheduleId() != scheduleId || anchor.getCurrentFlag() != 0)) {
			throw MyApplicationQueryException.invalidCursor();
		}
		var rows = repository.findPast(workerId, scheduleId, anchor == null ? null : anchor.getApplicationRound(),
			anchorId, size + 1);
		boolean hasMore = rows.size() > size;
		var visible = rows.subList(0, Math.min(size, rows.size()));
		String next = hasMore ? ApplicationHistoryCursor.encode(workerId, "HISTORY", scheduleId, "ALL",
			visible.getLast().getApplicationId()) : null;
		return new MyApplicationResponses.History(repository.findCurrentId(workerId, scheduleId).orElse(null),
			visible.stream().map(mapper::item).toList(), next, hasMore);
	}

	private Row owned(long workerId, long applicationId) {
		if (applicationId <= 0) throw MyApplicationQueryException.invalidRequest();
		// 타인의 지원과 존재하지 않는 지원을 같은 404로 처리한다.
		return repository.findOwned(workerId, applicationId).orElseThrow(MyApplicationQueryException::notFound);
	}
	private Row cursorAnchor(long workerId, long applicationId) {
		return repository.findOwned(workerId, applicationId).orElseThrow(MyApplicationQueryException::invalidCursor);
	}
	private void requireWorker(long workerId) {
		members.findById(workerId).filter(member -> member.memberRole().orElse(null) == MemberRole.WORKER)
			.orElseThrow(() -> new AccessDeniedException("Worker role required"));
	}
	private static int pageSize(String value) {
		if (value == null) return 20;
		if (!value.matches("[0-9]{1,3}")) throw MyApplicationQueryException.invalidRequest();
		int size = Integer.parseInt(value);
		if (size < 1 || size > 100) throw MyApplicationQueryException.invalidRequest();
		return size;
	}
}
