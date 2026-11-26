package com.wonday.insurance.platform;

import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** 접수 도메인에 연동 이벤트를 제공한다. MANDATORY 메서드는 접수 트랜잭션에 참여한다. */
@Service
@RequiredArgsConstructor
public class PlatformClaimEvents {

	private final PlatformWorkEventRepository events;
	private final PlatformLinkRepository links;
	private final FarmWorkClient farm;
	private final ObjectMapper json;
	private final Validator validator;
	private final Clock clock;

	@Transactional(readOnly = true)
	public List<Long> pendingIds() {
		return events.findPendingIntake(now(), PageRequest.of(0, 100));
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public Event lockPending(long eventId) {
		// 대기 ID를 읽은 뒤 다른 실행이 먼저 처리했을 수 있으므로 잠금 안에서 처리 가능 여부를 다시 확인한다.
		PlatformWorkEvent event = events.lockPendingIntake(eventId, now()).orElse(null);
		if (event == null) return null;
		PlatformLink original = links.findById(event.getPlatformLinkId()).orElseThrow();
		// 동의 갱신은 허용하되, 동일 근로자가 다른 보험 회원에 연결됐다고 과거 기록의 주인을 바꾸지 않는다.
		boolean active = original.getWorkerId() != null
			&& links.lockForClaimIntake(original.getMemberId(), original.getWorkerId()).isPresent();
		return new Event(event.getId(), original.getMemberId(), original.getWorkerId(), event.getAssignmentId(),
			event.getEventType(), event.getReasonCode(), event.getOccurredAt(), event.getWorkSnapshot(), active);
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public Work verifiedWork(Event event) {
		FarmWorkResponse.Assignment assignment;
		// 기존 스냅샷이 있으면 그대로 검증한다. 없는 이전 기록만 농업 상세 API로 보완한다.
		if (event.workSnapshot() == null) {
			assignment = farm.assignment(event.workerId(), event.assignmentId());
		} else {
			try {
				assignment = json.readValue(event.workSnapshot(), FarmWorkResponse.Assignment.class);
			} catch (RuntimeException exception) {
				throw FarmWorkClient.invalidResponse();
			}
		}
		if (assignment == null || !validator.validate(assignment).isEmpty()) {
			throw FarmWorkClient.invalidResponse();
		}
		FarmWorkClient.validateAssignment(assignment, event.workerId(), event.assignmentId());
		// 오프셋이 달라도 같은 순간끼리 비교하도록 UTC로 맞춘다. MySQL DATETIME(6)에 맞춰 정밀도도 통일한다.
		LocalDateTime confirmedAt = assignment.confirmedAt().withOffsetSameInstant(ZoneOffset.UTC)
			.toLocalDateTime().truncatedTo(ChronoUnit.MICROS);
		if (confirmedAt.isAfter(event.occurredAt())) throw FarmWorkClient.invalidResponse();
		String snapshot = event.workSnapshot();
		if (snapshot == null) {
			snapshot = json.writeValueAsString(assignment);
			events.findById(event.id()).orElseThrow().captureSnapshot(snapshot);
		}
		return new Work(snapshot, assignment.agreedWorkDate(), assignment.agreedTimeZone(),
			assignment.agreedStartAt().withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime().truncatedTo(ChronoUnit.MICROS),
			assignment.agreedLatitude(), assignment.agreedLongitude());
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void complete(long eventId, String result) {
		// 엔티티 변경은 호출한 접수 트랜잭션이 커밋될 때 접수·이력과 함께 반영된다.
		events.findById(eventId).orElseThrow().finishIntake(result, now());
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void defer(long eventId) {
		// 접수 실패 트랜잭션과 분리해 재시도 시각을 남긴다. 이미 처리된 이벤트는 다시 대기 상태로 돌리지 않는다.
		events.lockForIntakeRetry(eventId).filter(event -> event.getIntakeProcessedAt() == null)
			.ifPresent(event -> event.deferIntake(now().plusMinutes(1)));
	}

	private LocalDateTime now() {
		return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
	}

	// memberId는 보험 회원, workerId·assignmentId는 농업 서버의 외부 식별자다.
	public record Event(long id, long memberId, Long workerId, long assignmentId, String type,
		String reasonCode, LocalDateTime occurredAt, String workSnapshot, boolean activeLink) {}

	// 좌표가 모두 null이면 미확보다. 접수는 진행하고, 이후 관측소 선정 단계에서 이 상태를 구분한다.
	public record Work(String snapshot, LocalDate workDate, String timeZone, LocalDateTime startAt,
		BigDecimal latitude, BigDecimal longitude) {}
}
