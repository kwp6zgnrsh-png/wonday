package com.wonday.farm.application.validation;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import com.wonday.farm.application.exception.MyApplicationQueryException;

// 커서는 인증 토큰이 아니다. 조회 시 소유자 조건을 별도로 적용하고 실제 본인 앵커인지 검사한다.
// 커서는 계정·목록/과거이력·일정·필터에 묶인다. 시각은 서버가 소유자 범위 안의 원본 행에서 다시 읽는다.
public final class ApplicationHistoryCursor {
	private ApplicationHistoryCursor() {}
	public static String encode(long workerId, String scope, long scheduleId, String filter, long anchorId) {
		String value = "1|" + workerId + "|" + scope + "|" + scheduleId + "|" + filter + "|" + anchorId;
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}
	public static Long decode(String token, long workerId, String scope, long scheduleId, String filter) {
		if (token == null) return null;
		try {
			if (token.length() > 256 || !token.matches("[A-Za-z0-9_-]+")) throw MyApplicationQueryException.invalidCursor();
			String[] parts = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8).split("\\|", -1);
			if (parts.length != 6 || !parts[5].matches("[1-9][0-9]{0,18}")) throw MyApplicationQueryException.invalidCursor();
			long anchorId = Long.parseLong(parts[5]);
			if (!encode(workerId, scope, scheduleId, filter, anchorId).equals(token)) throw MyApplicationQueryException.invalidCursor();
			return anchorId;
		} catch (IllegalArgumentException exception) { throw MyApplicationQueryException.invalidCursor(); }
	}
}
