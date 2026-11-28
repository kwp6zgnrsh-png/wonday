package com.wonday.insurance.claim;

import com.wonday.insurance.member.MemberAccessService;
import jakarta.validation.Validator;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class AdminClaimQueryService {
	private final MemberAccessService members;
	private final AdminClaimRepository claims;
	private final ObjectMapper json;
	private final Validator validator;
	private final Clock clock;
	private final ClaimWeatherStationService stations;

	public AdminClaimView.Page<AdminClaimView.Summary> list(long memberId, String status, Long ownerId, Long beforeId, int limit) {
		members.requireAdmin(memberId); validatePage(beforeId,limit);
		// 저장된 상태 코드로 필터링한다. 조회 단계에서 승인·반려 상태나 전이 규칙을 새로 정의하지 않는다.
		if ((status != null && !status.matches("[A-Z][A-Z0-9_]{0,39}")) || (ownerId != null && ownerId <= 0)) throw AdminClaimQueryException.invalidQuery();
		return page(claims.list(status,ownerId,beforeId,PageRequest.of(0,limit+1)),limit,this::summary,AdminClaimRepository.SummaryRow::getId);
	}
	public AdminClaimView.Detail detail(long memberId, String rawId) {
		members.requireAdmin(memberId);
		var row=claims.detail(id(rawId)).orElseThrow(AdminClaimQueryException::notFound);
		var work=work(row);
		var contract=new AdminClaimView.Contract(row.getContractId(),row.getProductVersionId(),row.getProductCode(),row.getProductName(),
			row.getProductVersionNo(),contractStatus(row),at(row.getAcceptedAt()),at(row.getCoverageFrom()),at(row.getCoverageTo()),row.getTermsText());
		var source=new AdminClaimView.IntakeSource(row.getEventId(),row.getChangeId(),row.getSourceVersion(),row.getChangeType(),row.getReasonCode(),
			weatherReason(row.getSourcePayload()),row.getReasonDetail(),at(row.getOccurredAt()),at(row.getSourceUpdatedAt()),at(row.getReceivedAt()));
		return new AdminClaimView.Detail(summary(row),contract,source,work,stations.saved(row.getId()));
	}
	public AdminClaimView.Page<AdminClaimView.History> history(long memberId, String rawId, Long beforeId, int limit) {
		members.requireAdmin(memberId); validatePage(beforeId,limit);
		long claimId=id(rawId);
		claims.visibleId(claimId).orElseThrow(AdminClaimQueryException::notFound);
		return page(claims.history(claimId,beforeId,PageRequest.of(0,limit+1)),limit,
			row -> new AdminClaimView.History(row.getId(),row.getAction(),row.getFromStatus(),row.getToStatus(),row.getReason(),row.getActorId(),row.getAssessmentId(),at(row.getCreatedAt())),
			AdminClaimRepository.HistoryRow::getId);
	}
	private AdminClaimView.Summary summary(AdminClaimRepository.SummaryRow row) {
		return new AdminClaimView.Summary(row.getId(),row.getMemberId(),row.getMemberName(),row.getContractId(),row.getProductName(),row.getProductVersionNo(),
			row.getAssignmentId(),row.getStatus(),row.getOpenedVia(),at(row.getOpenedAt()),at(row.getUpdatedAt()));
	}
	private AdminClaimView.Work work(AdminClaimRepository.DetailRow row) {
		try {
			JsonNode tree=json.readTree(row.getWorkSnapshot());
			if (tree == null || !tree.isObject()) throw AdminClaimQueryException.invalidData();
			var work=json.treeToValue(tree,AdminClaimView.Work.class);
			if (!validator.validate(work).isEmpty() || work.assignmentId()!=row.getAssignmentId()
				|| (row.getWorkerId()!=null && !work.workerId().equals(row.getWorkerId()))
				|| ((work.agreedLatitude()==null)!=(work.agreedLongitude()==null))) throw AdminClaimQueryException.invalidData();
			LocalDate.parse(work.agreedWorkDate()); ZoneId.of(work.agreedTimeZone());
			OffsetDateTime.parse(work.confirmedAt()); OffsetDateTime.parse(work.agreedStartAt()); OffsetDateTime.parse(work.agreedEndAt());
			if (work.agreedBreakStartAt()!=null) OffsetDateTime.parse(work.agreedBreakStartAt());
			if (work.agreedBreakEndAt()!=null) OffsetDateTime.parse(work.agreedBreakEndAt());
			return work;
		} catch (RuntimeException invalid) {
			// 잘못된 JSON을 예외 원인에 연결하지 않아 번호 등이 섞인 원문을 응답·로그로 내보내지 않는다.
			throw AdminClaimQueryException.invalidData();
		}
	}
	private String weatherReason(String payload) {
		if (payload==null) return null; // 이전 JSON 미보관 이벤트의 세부 사유를 추측하지 않는다.
		try {
			var source=json.readTree(payload);
			if (source==null || !source.isObject()) throw AdminClaimQueryException.invalidData();
			var reason=source.get("weatherReasonCode");
			if (reason==null || reason.isNull()) return null;
			if (!reason.isString() || !reason.stringValue().matches("[A-Z][A-Z0-9_]{0,39}")) throw AdminClaimQueryException.invalidData();
			return reason.stringValue();
		} catch (RuntimeException invalid) { throw AdminClaimQueryException.invalidData(); }
	}
	private String contractStatus(AdminClaimRepository.DetailRow row) {
		LocalDateTime now=LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);
		if (row.getContractStatus().equals("ACTIVE")) {
			if (!row.getCoverageTo().isAfter(now)) return "EXPIRED";
			if (row.getCoverageFrom().isAfter(now)) return "PENDING";
		}
		return row.getContractStatus();
	}
	static long id(String raw) {
		try { if(raw!=null && raw.matches("[1-9][0-9]*")) return Long.parseLong(raw); }
		catch(NumberFormatException ignored) {}
		throw new AdminClaimQueryException(HttpStatus.BAD_REQUEST,"INVALID_CLAIM_ID","Invalid claim ID");
	}
	private static void validatePage(Long beforeId,int limit) { if ((beforeId!=null && beforeId<=0) || limit<1 || limit>100) throw AdminClaimQueryException.invalidQuery(); }
	private static OffsetDateTime at(LocalDateTime value) { return value==null?null:value.atOffset(ZoneOffset.UTC); }
	private static <T,V> AdminClaimView.Page<V> page(List<T> rows,int limit,Function<T,V> convert,Function<T,Long> id) {
		boolean more=rows.size()>limit; var visible=rows.subList(0,Math.min(rows.size(),limit));
		return new AdminClaimView.Page<>(visible.stream().map(convert).toList(),more,more?id.apply(visible.getLast()):null);
	}
}
