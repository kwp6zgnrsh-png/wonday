package com.wonday.farm.application.validation;

import static com.wonday.farm.application.testsupport.ApplicationTestData.KEY;
import static com.wonday.farm.application.testsupport.ApplicationTestData.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.LongStream;

import com.wonday.farm.application.dto.ApplyJobPostingRequest;
import com.wonday.farm.application.dto.ApplyJobPostingRequest.Schedule;
import com.wonday.farm.application.exception.ApplicationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

// 서비스 진입 시 수동 검증(요청 키·일정 목록·지문). Bean Validation·JSON 변환은 ApplyJobPostingRequestJsonTest에서 본다.
@DisplayName("APP-02 요청 검증·요청 지문 (단위)")
class ApplicationRequestValidatorTest {
	@Nested
	@DisplayName("Idempotency-Key")
	class RequestKey {
		@Test
		@DisplayName("UUID v4는 통과하고 대문자는 소문자로 정규화해 같은 키가 된다")
		void normalizes() {
			assertThat(ApplicationRequestValidator.requestKey(KEY)).isEqualTo(KEY);
			assertThat(ApplicationRequestValidator.requestKey(KEY.toUpperCase())).isEqualTo(KEY);
			assertThat(ApplicationRequestValidator.requestKey("3F2B8C1E-9a4d-4C6B-AE2F-1A2B3C4D5E6F"))
				.isEqualTo("3f2b8c1e-9a4d-4c6b-ae2f-1a2b3c4d5e6f");
		}

		@ParameterizedTest(name = "[{0}]")
		@NullAndEmptySource
		@ValueSource(strings = {
			"3f2b8c1e-9a4d-1c6b-8e2f-1a2b3c4d5e6f",   // v1
			"3f2b8c1e-9a4d-7c6b-8e2f-1a2b3c4d5e6f",   // v7
			"3f2b8c1e-9a4d-4c6b-ce2f-1a2b3c4d5e6f",   // variant c (RFC 4122 variant 아님)
			"3f2b8c1e9a4d4c6b8e2f1a2b3c4d5e6f",       // 하이픈 없음
			"{3f2b8c1e-9a4d-4c6b-8e2f-1a2b3c4d5e6f}",
			" 3f2b8c1e-9a4d-4c6b-8e2f-1a2b3c4d5e6f",
			"3f2b8c1e-9a4d-4c6b-8e2f-1a2b3c4d5e6f\n",
			"3f2b8c1e-9a4d-4c6b-8e2f-1a2b3c4d5e6",
			"00000000-0000-0000-0000-000000000000",
			"3f2b8c1e-9a4d-4c6b-8e2f-1a2b3c4d5e6f,3f2b8c1e-9a4d-4c6b-8e2f-1a2b3c4d5e70"})
		@DisplayName("누락·공백·다른 버전·잘못된 variant·형식 오류·쉼표로 합친 두 키는 400 INVALID_REQUEST")
		void rejects(String value) {
			assertInvalid(() -> ApplicationRequestValidator.requestKey(value));
		}
	}

	@Nested
	@DisplayName("일정 목록")
	class Schedules {
		@Test
		@DisplayName("1개·100개는 통과, 결과는 일정 ID 오름차순")
		void boundaries() {
			assertThat(ApplicationRequestValidator.schedules(101, request(201, 1))).hasSize(1);
			assertThat(ApplicationRequestValidator.schedules(101, requestOf(100))).hasSize(100)
				.extracting(Schedule::workScheduleId).isSorted();
			assertThat(ApplicationRequestValidator.schedules(101, request(203, 2, 201, 1, 202, 3)))
				.containsExactly(new Schedule(201L, 1), new Schedule(202L, 3), new Schedule(203L, 2));
		}

		@Test
		@DisplayName("0개·101개·null 목록·null 요청은 400")
		void sizeAndNulls() {
			assertInvalid(() -> ApplicationRequestValidator.schedules(101, new ApplyJobPostingRequest(List.of())));
			assertInvalid(() -> ApplicationRequestValidator.schedules(101, requestOf(101)));
			assertInvalid(() -> ApplicationRequestValidator.schedules(101, new ApplyJobPostingRequest(null)));
			assertInvalid(() -> ApplicationRequestValidator.schedules(101, null));
		}

		@Test
		@DisplayName("null 항목·null ID/버전·0 이하 ID/버전·중복 일정 ID(버전이 달라도)는 400")
		void items() {
			var withNull = new ArrayList<Schedule>(); withNull.add(new Schedule(201L, 1)); withNull.add(null);
			assertInvalid(() -> ApplicationRequestValidator.schedules(101, new ApplyJobPostingRequest(withNull)));
			assertInvalid(() -> ApplicationRequestValidator.schedules(101, new ApplyJobPostingRequest(List.of(new Schedule(null, 1)))));
			assertInvalid(() -> ApplicationRequestValidator.schedules(101, new ApplyJobPostingRequest(List.of(new Schedule(201L, null)))));
			assertInvalid(() -> ApplicationRequestValidator.schedules(101, request(0, 1)));
			assertInvalid(() -> ApplicationRequestValidator.schedules(101, request(-1, 1)));
			assertInvalid(() -> ApplicationRequestValidator.schedules(101, request(201, 0)));
			assertInvalid(() -> ApplicationRequestValidator.schedules(101, request(201, -3)));
			assertInvalid(() -> ApplicationRequestValidator.schedules(101, request(201, 1, 202, 1, 201, 2)));
		}

		@ParameterizedTest(name = "postingId={0}")
		@ValueSource(longs = {0, -1})
		@DisplayName("0 이하 공고 ID는 400")
		void postingId(long postingId) {
			assertInvalid(() -> ApplicationRequestValidator.schedules(postingId, request(201, 1)));
		}
	}

	@Nested
	@DisplayName("요청 지문")
	class Fingerprint {
		private String of(long postingId, ApplyJobPostingRequest request) {
			return ApplicationRequestValidator.fingerprint(postingId, ApplicationRequestValidator.schedules(postingId, request));
		}

		@Test
		@DisplayName("선택 순서가 달라도 같은 지문 (SHA-256 소문자 hex 64자)")
		void orderIndependent() {
			String a = of(101, request(201, 1, 202, 3));
			assertThat(of(101, request(202, 3, 201, 1))).isEqualTo(a);
			assertThat(a).matches("[0-9a-f]{64}");
		}

		@Test
		@DisplayName("공고·일정·버전·일정 수가 바뀌면 지문도 달라진다")
		void changes() {
			String base = of(101, request(201, 1, 202, 3));
			assertThat(List.of(of(102, request(201, 1, 202, 3)), of(101, request(201, 1, 203, 3)),
				of(101, request(201, 1, 202, 4)), of(101, request(201, 1)), of(101, request(201, 1, 202, 3, 203, 1))))
				.doesNotContain(base).doesNotHaveDuplicates();
		}

		@Test
		@DisplayName("자리 경계가 다른 입력이 같은 문자열이 되지 않는다 (201:12 / 2011:2, 공고 10|1 / 1|01)")
		void separatorsAvoidCollisions() {
			assertThat(of(101, request(201, 12))).isNotEqualTo(of(101, request(2011, 2)));
			assertThat(of(10, request(1, 1))).isNotEqualTo(of(1, request(1, 1)));
		}
	}

	private static ApplyJobPostingRequest requestOf(int count) {
		long[] pairs = LongStream.rangeClosed(1, count).flatMap(i -> LongStream.of(1000 - i, 1)).toArray();
		return request(Arrays.copyOf(pairs, pairs.length));
	}

	private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
		assertThatThrownBy(call).isInstanceOfSatisfying(ApplicationException.class, error -> {
			assertThat(error.status()).isEqualTo(HttpStatus.BAD_REQUEST);
			assertThat(error.code()).isEqualTo("INVALID_REQUEST");
		});
	}
}
