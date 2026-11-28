package com.wonday.farm.application.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import com.wonday.farm.application.dto.ConditionConfirmation;
import com.wonday.farm.application.repository.MyApplicationQueryRepository.Row;

// 최초 지원과 마지막 명시적 재확인을 분리한다. 최신 조건이 바뀌어도 과거 확인 이력은 남는다.
public final class ConditionConfirmationMapper {
    private ConditionConfirmationMapper() {}
    public static ConditionConfirmation from(Row row) {
        return of(row.getAppliedTermsVersion(), row.getLatestTermsVersion(), row.getAcknowledgementId(),
            row.getAcknowledgedTermsVersion(), row.getAcknowledgedAtUtc());
    }
    public static ConditionConfirmation of(Integer initial, int latest, Long id, Integer version, LocalDateTime atUtc) {
        boolean initialMatches = initial != null && initial == latest;
        boolean acknowledgedMatches = id != null && version != null && version == latest;
        String source = initialMatches ? "INITIAL_APPLICATION"
            : id != null && version != null ? "RECONFIRMATION"
            : initial != null ? "INITIAL_APPLICATION" : "NONE";
        Integer confirmed = "RECONFIRMATION".equals(source) ? version : initial;
        return new ConditionConfirmation(source, confirmed, initialMatches || acknowledgedMatches,
            id, version, atUtc == null ? null : atUtc.atOffset(ZoneOffset.UTC).withOffsetSameInstant(ZoneOffset.ofHours(9)));
    }
}
