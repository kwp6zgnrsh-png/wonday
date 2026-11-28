package com.wonday.insurance.claim;

import com.wonday.insurance.api.ApiTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

final class ClaimWorkResponseTime {
	private ClaimWorkResponseTime() {}
	static JsonNode gmt9(JsonNode storedWork) {
		try {
			if (!(storedWork instanceof ObjectNode object)) throw new IllegalArgumentException();
			// 응답용 복사본의 알려진 일시만 변환한다. 원본 JSON·약정 날짜·업무 시간대·기타 문자열은 보존한다.
			ObjectNode response = object.deepCopy();
			for (String field : List.of("confirmedAt", "agreedStartAt", "agreedEndAt", "agreedBreakStartAt", "agreedBreakEndAt")) {
				var value = response.get(field);
				if (value == null || value.isNull()) continue;
				if (!value.isString()) throw new IllegalArgumentException();
				response.put(field, ApiTime.fromOffsetString(value.stringValue()));
			}
			return response;
		} catch (RuntimeException invalid) {
			// 날짜 파싱 예외에는 저장된 원문이 들어갈 수 있으므로 원인 예외를 외부 응답/로그에 연결하지 않는다.
			throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Stored work time requires verification");
		}
	}
}
