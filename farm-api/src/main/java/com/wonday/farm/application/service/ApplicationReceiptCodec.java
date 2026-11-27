package com.wonday.farm.application.service;

import com.wonday.farm.application.dto.ApplyJobPostingResponse;
import org.springframework.stereotype.Component;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

// 성공 당시 응답을 보관한다. 현재 지원 상태·조건으로 최초 응답을 재구성하지 않는다.
@Component
public class ApplicationReceiptCodec {
	// 저장 응답의 오프셋을 읽고 쓸 때 UTC 문맥으로 바꾸지 않는다.
	private final JsonMapper mapper = JsonMapper.builder()
		.disable(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
		.disable(DateTimeFeature.WRITE_DATES_WITH_CONTEXT_TIME_ZONE)
		.disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
		.build();

	public String encode(ApplyJobPostingResponse response) { return mapper.writeValueAsString(response); }

	public ApplyJobPostingResponse decode(String payload) {
		return mapper.readValue(payload, ApplyJobPostingResponse.class);
	}
}