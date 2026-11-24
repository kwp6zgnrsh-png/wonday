package com.wonday.farm.insurance.service;

import com.wonday.farm.insurance.dto.WorkChangesResponse;
import com.wonday.farm.insurance.entity.ChangeCursor;
import com.wonday.farm.insurance.entity.WorkChangeVersion;
import com.wonday.farm.insurance.exception.InsuranceApiException;
import com.wonday.farm.insurance.repository.ChangeCursorRepository;
import com.wonday.farm.insurance.repository.ChangeStreamRepository;
import com.wonday.farm.insurance.repository.WorkChangeVersionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkChangeQueryService {
	private final InsuranceDataAccess access;
	private final ChangeStreamRepository streams;
	private final ChangeCursorRepository cursors;
	private final WorkChangeVersionRepository versions;

	public WorkChangeQueryService(InsuranceDataAccess access, ChangeStreamRepository streams,
			ChangeCursorRepository cursors, WorkChangeVersionRepository versions) {
		this.access = access;
		this.streams = streams;
		this.cursors = cursors;
		this.versions = versions;
	}

	// 조회 커서도 영속화하므로 readOnly가 아니다. 기록 저장과 동일한 근로자 잠금을 사용한다.
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public WorkChangesResponse poll(long workerId, String cursor, int limit) {
		if (limit < 1 || limit > 500) throw InsuranceApiException.invalidRequest();
		access.requireWorker(workerId);
		streams.ensureExists(workerId);
		var stream = streams.lockWorker(workerId).orElseThrow(InsuranceApiException::unavailable);
		long after = 0;
		if (cursor != null) {
			if (!cursor.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
				throw invalidCursor();
			}
			var position = cursors.findById(cursor).filter(value -> value.getWorkerId() == workerId)
				.orElseThrow(WorkChangeQueryService::invalidCursor);
			after = position.getAfterSequence();
			if (after > stream.getLastSequence()) throw invalidCursor();
		}
		var found = versions.findByWorkerIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(workerId, after,
			PageRequest.of(0, limit + 1));
		boolean hasMore = found.size() > limit;
		var page = found.subList(0, Math.min(found.size(), limit));
		long next = page.isEmpty() ? after : page.getLast().getSequenceNo();
		var position = cursors.findByWorkerIdAndAfterSequence(workerId, next)
			.orElseGet(() -> cursors.save(new ChangeCursor(workerId, next)));
		return new WorkChangesResponse(workerId, page.stream().map(WorkChangeQueryService::item).toList(),
			position.getToken(), hasMore);
	}

	private static WorkChangesResponse.Item item(WorkChangeVersion version) {
		var zone = InsuranceTime.zone(version.getEventTimeZone());
		return new WorkChangesResponse.Item(version.getChangeId(), Long.toString(version.getSourceVersion()),
			version.getAssignmentId(), version.getChangeType(), version.getReasonCode(), version.getWeatherReasonCode(),
			version.getReasonDetail(), InsuranceTime.utc(version.getEffectiveAt(), zone),
			InsuranceTime.utc(version.getRecordedAt(), zone), InsuranceTime.utc(version.getUpdatedAt(), zone));
	}

	private static InsuranceApiException invalidCursor() {
		return new InsuranceApiException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "유효한 해당 근로자의 커서를 사용해 주세요.");
	}
}
