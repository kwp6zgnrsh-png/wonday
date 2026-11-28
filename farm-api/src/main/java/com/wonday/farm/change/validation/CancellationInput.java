package com.wonday.farm.change.validation;

import com.wonday.farm.change.dto.WorkCancellationRequest;
import com.wonday.farm.change.entity.WorkChange;
import com.wonday.farm.change.exception.WorkCancellationException;

public final class CancellationInput {
    private CancellationInput() {}

    public static long id(String value) {
        try {
            if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw WorkCancellationException.invalid();
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw WorkCancellationException.invalid();
        }
    }

    public static ReasonInput reason(WorkCancellationRequest request) {
        if (request == null || request.reasonCode() == null) throw WorkCancellationException.invalid();
        WorkChange.Reason reason;
        WorkChange.WeatherReason weather;
        try {
            reason = WorkChange.Reason.valueOf(request.reasonCode());
            weather = request.weatherReasonCode() == null ? null
                : WorkChange.WeatherReason.valueOf(request.weatherReasonCode());
        } catch (IllegalArgumentException exception) {
            throw WorkCancellationException.invalid();
        }
        return reason(reason, weather, request.reasonDetail());
    }

    public static ReasonInput reason(WorkChange.Reason reason, WorkChange.WeatherReason weather, String rawDetail) {
        if (reason == null || (reason == WorkChange.Reason.WEATHER) != (weather != null)) {
            throw WorkCancellationException.invalid();
        }
        String detail = normalizeDetail(rawDetail);
        // Java String.length()의 UTF-16 길이가 아니라 유니코드 코드 포인트 수로 검사한다.
        if (detail != null && detail.codePointCount(0, detail.length()) > 500) {
            throw WorkCancellationException.detailTooLong();
        }
        return new ReasonInput(reason, weather, detail);
    }

    // 줄바꿈·탭·유니코드 공백은 양 끝에서만 제거한다. 중간 내용은 변경하지 않는다.
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

    public record ReasonInput(WorkChange.Reason reason, WorkChange.WeatherReason weather, String detail) {}
}
