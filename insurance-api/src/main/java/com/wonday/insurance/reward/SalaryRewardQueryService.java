package com.wonday.insurance.reward;

import com.wonday.insurance.member.MemberAccessService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
class SalaryRewardQueryService {

	private final MemberAccessService members;
	private final SalaryRewardCheckRepository checks;

	@Transactional(readOnly = true)
	public SalaryRewardView latest(long memberId) {
		members.requireWorker(memberId);
		return checks.findFirstByMemberIdOrderByIdDesc(memberId).map(SalaryRewardView::from)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No reward check history"));
	}

	@Transactional(readOnly = true)
	public History history(long memberId, int limit, Long beforeId) {
		if (limit < 1 || limit > 100 || (beforeId != null && beforeId <= 0)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid reward history page");
		}
		members.requireWorker(memberId);
		var rows = checks.history(memberId, beforeId, PageRequest.of(0, limit + 1));
		boolean more = rows.size() > limit;
		var items = rows.stream().limit(limit).map(SalaryRewardView::from).toList();
		return new History(items, more, more ? items.getLast().id() : null);
	}

	record History(List<SalaryRewardView> items, boolean hasMore, Long nextBeforeId) {}
}
