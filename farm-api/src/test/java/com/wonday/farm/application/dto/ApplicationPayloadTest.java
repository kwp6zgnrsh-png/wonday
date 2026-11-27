package com.wonday.farm.application.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;

import com.wonday.farm.application.service.ApplicationReceiptCodec;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

// 요청 JSON 변환(엄격한 정수 역직렬화)·Bean Validation 선언과 성공 응답 보관 codec.
// JsonMapper는 기본 빌더다. Spring Boot가 구성한 JsonMapper 설정 그대로인지는 검증하지 않는다.
@DisplayName("APP-02 요청 JSON·응답 보관 codec (단위)")
class ApplicationPayloadTest {
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static jakarta.validation.ValidatorFactory factory;
	private static Validator validator;

	@BeforeAll
	static void validator() {
		factory = Validation.buildDefaultValidatorFactory();
		validator = factory.getValidator();
	}

	@AfterAll
	static void close() { factory.close(); }

	private static ApplyJobPostingRequest read(String json) {
		return JSON.readValue(json, ApplyJobPostingRequest.class);
	}

	@Nested
	@DisplayName("요청 JSON")
	class RequestJson {
		@Test
		@DisplayName("JSON 정수 ID·버전은 그대로 읽는다")
		void integers() {
			var request = read("{\"schedules\":[{\"workScheduleId\":201,\"termsVersion\":1},{\"workScheduleId\":9223372036854775807,\"termsVersion\":2147483647}]}");
			assertThat(request.schedules()).containsExactly(new ApplyJobPostingRequest.Schedule(201L, 1),
				new ApplyJobPostingRequest.Schedule(Long.MAX_VALUE, Integer.MAX_VALUE));
		}

		@ParameterizedTest(name = "{0}")
		@ValueSource(strings = {
			"{\"schedules\":[{\"workScheduleId\":\"201\",\"termsVersion\":1}]}",
			"{\"schedules\":[{\"workScheduleId\":201,\"termsVersion\":\"1\"}]}",
			"{\"schedules\":[{\"workScheduleId\":201.0,\"termsVersion\":1}]}",
			"{\"schedules\":[{\"workScheduleId\":201,\"termsVersion\":1.5}]}",
			"{\"schedules\":[{\"workScheduleId\":2e2,\"termsVersion\":1}]}",
			"{\"schedules\":[{\"workScheduleId\":true,\"termsVersion\":1}]}",
			"{\"schedules\":[{\"workScheduleId\":9223372036854775808,\"termsVersion\":1}]}",
			"{\"schedules\":[{\"workScheduleId\":201,\"termsVersion\":2147483648}]}",
			"{\"schedules\":[{\"workScheduleId\":[201],\"termsVersion\":1}]}"})
		@DisplayName("문자열·소수·지수·불리언·범위 초과·배열 숫자는 자동 변환하지 않고 JSON 오류")
		void strict(String json) {
			assertThatThrownBy(() -> read(json)).isInstanceOf(JacksonException.class);
		}

		@Test
		@DisplayName("Bean Validation: null 목록·빈 목록·101개·null 항목·null/0/음수 값은 위반, 1개·100개는 통과")
		void beanValidation() {
			assertThat(validator.validate(read("{\"schedules\":null}"))).isNotEmpty();
			assertThat(validator.validate(read("{}"))).isNotEmpty();
			assertThat(validator.validate(read("{\"schedules\":[]}"))).isNotEmpty();
			assertThat(validator.validate(read("{\"schedules\":[null]}"))).isNotEmpty();
			assertThat(validator.validate(read("{\"schedules\":[{\"workScheduleId\":null,\"termsVersion\":1}]}"))).isNotEmpty();
			assertThat(validator.validate(read("{\"schedules\":[{\"workScheduleId\":0,\"termsVersion\":1}]}"))).isNotEmpty();
			assertThat(validator.validate(read("{\"schedules\":[{\"workScheduleId\":1,\"termsVersion\":-1}]}"))).isNotEmpty();
			assertThat(validator.validate(read("{\"schedules\":[{\"workScheduleId\":1,\"termsVersion\":1}]}"))).isEmpty();
			assertThat(validator.validate(read(schedulesJson(100)))).isEmpty();
			assertThat(validator.validate(read(schedulesJson(101)))).isNotEmpty();
		}

		@Test
		@DisplayName("요청 DTO에는 workerId·employerId 성분이 없다 (소유자는 세션에서만)")
		void noOwnerFields() {
			assertThat(Arrays.stream(ApplyJobPostingRequest.class.getRecordComponents()).map(c -> c.getName())).containsExactly("schedules");
			assertThat(Arrays.stream(ApplyJobPostingRequest.Schedule.class.getRecordComponents()).map(c -> c.getName()))
				.containsExactly("workScheduleId", "termsVersion");
		}

		private static String schedulesJson(int count) {
			var items = new StringBuilder();
			for (int i = 1; i <= count; i++) items.append(i == 1 ? "" : ",").append("{\"workScheduleId\":").append(i).append(",\"termsVersion\":1}");
			return "{\"schedules\":[" + items + "]}";
		}
	}

	@Nested
	@DisplayName("성공 응답 보관 codec")
	class Codec {
		private final ApplicationReceiptCodec codec = new ApplicationReceiptCodec();

		private ApplyJobPostingResponse response(String appliedAt) {
			return new ApplyJobPostingResponse(101, List.of(
				new ApplyJobPostingResponse.Application(9001, 201, 1, "PENDING", 1, OffsetDateTime.parse(appliedAt)),
				new ApplyJobPostingResponse.Application(9002, 202, 2, "PENDING", 3, OffsetDateTime.parse(appliedAt))));
		}

		@Test
		@DisplayName("encode → decode 후 ID·회차·상태·버전·접수 시각(+09:00·마이크로초)이 그대로다")
		void roundTrip() {
			var original = response("2026-12-01T10:02:03.456789+09:00");

			String payload = codec.encode(original);
			var decoded = codec.decode(payload);

			assertThat(decoded).isEqualTo(original);
			assertThat(decoded.applications().get(0).appliedAt().getOffset().getTotalSeconds()).isEqualTo(9 * 3600);
			assertThat(decoded.applications().get(0).appliedAt().getNano()).isEqualTo(456789000);
			assertThat(payload).contains("\"appliedAt\":\"2026-12-01T10:02:03.456789+09:00\"").doesNotContain("Z\"", "01:02:03");
		}

		@Test
		@DisplayName("마이크로초 0·다른 offset(-05:00)도 offset을 UTC로 바꾸지 않는다")
		void otherOffsets() {
			for (String value : List.of("2026-12-01T10:02:03+09:00", "2026-12-01T10:02:03.000001-05:00")) {
				var decoded = codec.decode(codec.encode(response(value)));
				assertThat(decoded.applications().get(1).appliedAt()).isEqualTo(OffsetDateTime.parse(value));
				assertThat(decoded.applications().get(1).appliedAt().getOffset()).isEqualTo(OffsetDateTime.parse(value).getOffset());
			}
		}

		@Test
		@DisplayName("보관 JSON 필드는 jobPostingId·applications와 항목 6개뿐 (회원·세션·CSRF·토큰 필드 없음)")
		void fields() {
			var node = JSON.readTree(codec.encode(response("2026-12-01T10:02:03.456789+09:00")));
			assertThat(node.propertyNames()).containsExactlyInAnyOrder("jobPostingId", "applications");
			assertThat(node.get("applications").get(0).propertyNames()).containsExactlyInAnyOrder("applicationId", "workScheduleId",
				"applicationRound", "status", "appliedTermsVersion", "appliedAt");
			assertThat(node.toString()).doesNotContainIgnoringCase("worker").doesNotContainIgnoringCase("csrf")
				.doesNotContainIgnoringCase("token").doesNotContainIgnoringCase("session");
		}

		@Test
		@DisplayName("응답 applications 목록은 생성 후 외부 변경과 분리된다 (불변 복사)")
		void immutableList() {
			var list = new java.util.ArrayList<>(response("2026-12-01T10:02:03+09:00").applications());
			var body = new ApplyJobPostingResponse(101, list);
			list.clear();
			assertThat(body.applications()).hasSize(2);
			assertThatThrownBy(() -> body.applications().clear()).isInstanceOf(UnsupportedOperationException.class);
		}

		@Test
		@DisplayName("[특성화] 기본 JsonMapper로 직렬화해도 @JsonFormat 덕분에 +09:00을 유지한다 (API 응답 경로 근거)")
		void defaultMapperKeepsOffset() {
			String json = JSON.writeValueAsString(response("2026-12-01T10:02:03.456789+09:00"));
			assertThat(json).contains("\"appliedAt\":\"2026-12-01T10:02:03.456789+09:00\"");
		}
	}
}
