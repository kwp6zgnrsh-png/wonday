package com.wonday.farm.work.validation;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import com.wonday.farm.work.exception.WorkApplicantException;

// 페이지 위치일 뿐 인증 수단은 아니다. 저장소가 공고 소유자·날짜를 별도로 검사한다.
public final class WorkApplicantCursor {
	private WorkApplicantCursor() {}
	public static String encode(long employerId, long postingId, long scheduleId, String filter, long anchorId) {
		String data = "1|" + employerId + "|" + postingId + "|" + scheduleId + "|" + filter + "|" + anchorId;
		return Base64.getUrlEncoder().withoutPadding().encodeToString(data.getBytes(StandardCharsets.UTF_8));
	}
	public static Long decode(String token, long employerId, long postingId, long scheduleId, String filter) {
		if (token == null) return null;
		try {
			if (token.length() > 256 || !token.matches("[A-Za-z0-9_-]+")) throw WorkApplicantException.cursor();
			String[] fields = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8).split("\\|", -1);
			if (fields.length != 6 || !fields[5].matches("[1-9][0-9]{0,18}")) throw WorkApplicantException.cursor();
			long id = Long.parseLong(fields[5]);
			if (!encode(employerId, postingId, scheduleId, filter, id).equals(token)) throw WorkApplicantException.cursor();
			return id;
		} catch (IllegalArgumentException exception) { throw WorkApplicantException.cursor(); }
	}
}
