package com.wonday.farm.application.validation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import com.wonday.farm.application.dto.ApplyJobPostingRequest;
import com.wonday.farm.application.exception.ApplicationException;

public final class ApplicationRequestValidator {
	private static final Pattern UUID_V4 = Pattern.compile(
		"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}");

	private ApplicationRequestValidator() {}

	public static String requestKey(String value) {
		if (value == null || !UUID_V4.matcher(value).matches()) throw ApplicationException.invalidRequest();
		return value.toLowerCase(Locale.ROOT);
	}

	public static List<ApplyJobPostingRequest.Schedule> schedules(long postingId, ApplyJobPostingRequest request) {
		if (postingId <= 0 || request == null || request.schedules() == null
				|| request.schedules().isEmpty() || request.schedules().size() > 100) {
			throw ApplicationException.invalidRequest();
		}
		var ids = new HashSet<Long>();
		for (var schedule : request.schedules()) {
			if (schedule == null || schedule.workScheduleId() == null || schedule.workScheduleId() <= 0
					|| schedule.termsVersion() == null || schedule.termsVersion() <= 0
					|| !ids.add(schedule.workScheduleId())) throw ApplicationException.invalidRequest();
		}
		// 선택 순서가 바뀌어도 같은 요청이다. 잠금과 응답 순서도 일정 ID 오름차순으로 통일한다.
		return request.schedules().stream().sorted(Comparator.comparing(ApplyJobPostingRequest.Schedule::workScheduleId)).toList();
	}

	public static String fingerprint(long postingId, List<ApplyJobPostingRequest.Schedule> schedules) {
		var canonical = new StringBuilder().append(postingId).append('|');
		for (var schedule : schedules) {
			canonical.append(schedule.workScheduleId()).append(':').append(schedule.termsVersion()).append(';');
		}
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 unavailable", exception);
		}
	}
}