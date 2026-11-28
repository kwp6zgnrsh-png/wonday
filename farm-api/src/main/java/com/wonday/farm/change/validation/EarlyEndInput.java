package com.wonday.farm.change.validation;

import java.time.OffsetDateTime;
import com.wonday.farm.change.dto.WorkEarlyEndRequest;
import com.wonday.farm.change.entity.WorkChange;
import com.wonday.farm.change.exception.WorkEarlyEndException;

public final class EarlyEndInput {
    private EarlyEndInput() {}

    public static long id(String value) {
        try {
            if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw WorkEarlyEndException.invalid();
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw WorkEarlyEndException.invalid();
        }
    }

    public static Input request(WorkEarlyEndRequest request) {
        if (request == null || request.reasonCode() == null) throw WorkEarlyEndException.invalid();
        WorkChange.Reason reason;
        WorkChange.WeatherReason weather;
        try {
            reason = WorkChange.Reason.valueOf(request.reasonCode());
            weather = request.weatherReasonCode() == null ? null
                : WorkChange.WeatherReason.valueOf(request.weatherReasonCode());
        } catch (IllegalArgumentException exception) {
            throw WorkEarlyEndException.invalid();
        }
        return values(request.actualEndAt(), reason, weather, request.reasonDetail());
    }

    public static Input values(OffsetDateTime actualEndAt, WorkChange.Reason reason,
            WorkChange.WeatherReason weather, String rawDetail) {
        // JSON 외의 내부 호출도 DB와 같은 마이크로초 정밀도를 지킨다.
        if (actualEndAt == null || actualEndAt.getNano() % 1000 != 0 || reason == null
                || (reason == WorkChange.Reason.WEATHER) != (weather != null)) {
            throw WorkEarlyEndException.invalid();
        }
        String detail = normalizeDetail(rawDetail);
        if (detail != null && detail.codePointCount(0, detail.length()) > 500) {
            throw WorkEarlyEndException.detailTooLong();
        }
        return new Input(actualEndAt, reason, weather, detail);
    }

    // 취소와 같은 기준이다. 양 끝의 공백만 제거하고 중간 공백·줄바꿈은 보존한다.
    public static String normalizeDetail(String value) {
        if (value == null) return null;
        int start = 0, end = value.length();
        while (start < end) {
            int point = value.codePointAt(start);
            if (!space(point)) break;
            start += Character.charCount(point);
        }
        while (end > start) {
            int point = value.codePointBefore(end);
            if (!space(point)) break;
            end -= Character.charCount(point);
        }
        return start == end ? null : value.substring(start, end);
    }

    private static boolean space(int point) {
        return Character.isWhitespace(point) || Character.isSpaceChar(point);
    }

    public record Input(OffsetDateTime actualEndAt, WorkChange.Reason reason,
        WorkChange.WeatherReason weather, String detail) {}
}
