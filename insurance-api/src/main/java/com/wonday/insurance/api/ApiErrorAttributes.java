package com.wonday.insurance.api;

import java.time.Instant;
import java.util.Date;
import java.util.Map;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.webmvc.error.DefaultErrorAttributes;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.WebRequest;

/** Spring 기본 JSON 오류의 timestamp도 정상 응답과 같은 오프셋을 사용한다. 상태·오류 정보 정책은 유지한다. */
@Component
class ApiErrorAttributes extends DefaultErrorAttributes {
	@Override
	public Map<String, Object> getErrorAttributes(WebRequest request, ErrorAttributeOptions options) {
		var attributes = super.getErrorAttributes(request, options);
		Object timestamp = attributes.get("timestamp");
		if (timestamp instanceof Date date) attributes.put("timestamp", ApiTime.fromInstant(date.toInstant()));
		else if (timestamp instanceof Instant instant) attributes.put("timestamp", ApiTime.fromInstant(instant));
		return attributes;
	}
}
