package com.wonday.farm.insurance.service;

import static com.wonday.farm.insurance.testsupport.InsuranceFixtures.assignment;
import static com.wonday.farm.insurance.testsupport.InsuranceFixtures.member;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import com.wonday.farm.insurance.dto.WorkAssignmentResponse;
import com.wonday.farm.insurance.exception.InsuranceApiException;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import com.wonday.farm.work.entity.WorkAssignment;
import com.wonday.farm.work.repository.WorkAssignmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// 보험 약정 상세의 agreedLatitude·agreedLongitude. Repository는 mock, 약정은 실제 WorkAssignment(리플렉션으로 값 주입).
// 신규 선정·확정 API와 공고 위치 수정 API는 아직 없으므로 '확정 시 좌표 복사'·'공고 수정 후 약정 보존'은 검증하지 않는다.
// findOwned의 실제 SQL 소유 필터와 DECIMAL 컬럼 매핑도 실제 DB 없이 증명하지 않는다.
@DisplayName("보험 약정 상세의 좌표 (WorkAssignmentQueryService)")
class WorkAssignmentCoordinatesTest {
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final long WORKER_ID = 5L;
	private static final long ASSIGNMENT_ID = 31L;

	private final MemberRepository members = mock(MemberRepository.class);
	private final WorkAssignmentRepository assignments = mock(WorkAssignmentRepository.class);
	private final WorkAssignmentQueryService service =
		new WorkAssignmentQueryService(new InsuranceDataAccess(members), assignments);
	private WorkAssignment stored;

	@BeforeEach
	void setUp() {
		given(members.findById(WORKER_ID)).willReturn(Optional.of(member(WORKER_ID, MemberRole.WORKER)));
		stored = assignment(ASSIGNMENT_ID);
		given(assignments.findOwned(WORKER_ID, ASSIGNMENT_ID)).willReturn(Optional.of(stored));
	}

	@Nested
	@DisplayName("정상 반환")
	class Returned {
		@Test
		@DisplayName("약정에 저장된 좌표를 그대로(같은 객체·scale 6) 반환하고 다른 약정 값도 기존대로 매핑한다")
		void storedCoordinates() {
			coordinates("37.123457", "127.987654");

			var response = service.find(WORKER_ID, ASSIGNMENT_ID);

			assertThat(response.agreedLatitude()).isSameAs(stored.getAgreedLatitude());
			assertThat(response.agreedLongitude()).isSameAs(stored.getAgreedLongitude());
			assertThat(response.agreedLatitude().toPlainString()).isEqualTo("37.123457");
			assertThat(response.agreedLongitude().toPlainString()).isEqualTo("127.987654");
			assertThat(response.agreedWorkLocation()).isEqualTo("약정 작업지");
			assertThat(response.agreedDailyWage()).isEqualTo(120000L);
			assertThat(response.agreedStartAt()).hasToString("2026-11-30T08:00+09:00");
			assertThat(response.agreedBreakStartAt()).hasToString("2026-11-30T12:00+09:00");
		}

		@Test
		@DisplayName("두 좌표가 null인 기존 약정은 다른 필수 약정 정보가 정상이면 두 필드 null로 정상 반환한다")
		void legacyNullCoordinates() {
			var response = service.find(WORKER_ID, ASSIGNMENT_ID);

			assertThat(response.agreedLatitude()).isNull();
			assertThat(response.agreedLongitude()).isNull();
			assertThat(response.assignmentId()).isEqualTo(ASSIGNMENT_ID);
		}

		@Test
		@DisplayName("경계·음수·0 좌표도 그대로 반환한다 (서비스는 반올림·보정하지 않음)")
		void boundaryValues() {
			coordinates("-90.000000", "180.000000");
			assertThat(service.find(WORKER_ID, ASSIGNMENT_ID).agreedLatitude().toPlainString()).isEqualTo("-90.000000");
			coordinates("0.000000", "0.000000");
			var zero = service.find(WORKER_ID, ASSIGNMENT_ID);
			assertThat(zero.agreedLatitude()).isEqualByComparingTo("0");
			assertThat(zero.agreedLongitude()).isEqualByComparingTo("0");
		}

		@Test
		@DisplayName("JSON 응답에 두 필드가 숫자 또는 명시적 null로 존재한다")
		void json() {
			JsonNode empty = JSON.readTree(JSON.writeValueAsString(service.find(WORKER_ID, ASSIGNMENT_ID)));
			assertThat(empty.has("agreedLatitude")).isTrue();
			assertThat(empty.has("agreedLongitude")).isTrue();
			assertThat(empty.get("agreedLatitude").isNull()).isTrue();
			assertThat(empty.get("agreedLongitude").isNull()).isTrue();

			coordinates("37.123457", "-127.987654");
			JsonNode filled = JSON.readTree(JSON.writeValueAsString(service.find(WORKER_ID, ASSIGNMENT_ID)));
			assertThat(filled.get("agreedLatitude").isNumber()).isTrue();
			assertThat(filled.get("agreedLatitude").decimalValue()).isEqualByComparingTo("37.123457");
			assertThat(filled.get("agreedLongitude").decimalValue()).isEqualByComparingTo("-127.987654");
		}

		@Test
		@DisplayName("상세를 반복 조회해도 약정 값이 바뀌지 않고, 약정 Repository의 findOwned만 사용한다 (현재 공고 조회 없음)")
		void repeatedReadsDoNotChangeOrLookUpPosting() {
			coordinates("37.123457", "127.987654");
			var before = snapshot(stored);

			WorkAssignmentResponse first = service.find(WORKER_ID, ASSIGNMENT_ID);
			WorkAssignmentResponse second = service.find(WORKER_ID, ASSIGNMENT_ID);

			assertThat(second).isEqualTo(first);
			assertThat(snapshot(stored)).isEqualTo(before);
			verify(assignments, times(2)).findOwned(WORKER_ID, ASSIGNMENT_ID);
			verifyNoMoreInteractions(assignments);
		}

		@Test
		@DisplayName("legacy null 약정도 반복 조회 후 null로 남는다 (보완 저장 없음)")
		void legacyStaysNull() {
			service.find(WORKER_ID, ASSIGNMENT_ID);
			service.find(WORKER_ID, ASSIGNMENT_ID);

			assertThat(stored.getAgreedLatitude()).isNull();
			assertThat(stored.getAgreedLongitude()).isNull();
			verify(assignments, times(2)).findOwned(WORKER_ID, ASSIGNMENT_ID);
			verifyNoMoreInteractions(assignments);
		}
	}

	@Nested
	@DisplayName("오류")
	class Errors {
		@ParameterizedTest(name = "lat={0}, lon={1}")
		@CsvSource(nullValues = "NULL", value = {"37.123457, NULL", "NULL, 127.987654", "0.000000, NULL", "NULL, 0.000000"})
		@DisplayName("한쪽 좌표만 있으면 503 SERVICE_UNAVAILABLE (0도 누락으로 보지 않음)")
		void halfCoordinates(String latitude, String longitude) {
			coordinates(latitude, longitude);

			assertError(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE");
		}

		@Test
		@DisplayName("좌표가 null이어도 기존 필수 약정 정보(근무일·확정 시각·휴게 쌍) 누락은 기존대로 503")
		void existingRequiredFieldsStillChecked() {
			ReflectionTestUtils.setField(stored, "agreedWorkDate", null);
			assertError(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE");

			stored = assignment(ASSIGNMENT_ID);
			given(assignments.findOwned(WORKER_ID, ASSIGNMENT_ID)).willReturn(Optional.of(stored));
			ReflectionTestUtils.setField(stored, "confirmedAtUtc", null);
			assertError(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE");

			stored = assignment(ASSIGNMENT_ID);
			given(assignments.findOwned(WORKER_ID, ASSIGNMENT_ID)).willReturn(Optional.of(stored));
			ReflectionTestUtils.setField(stored, "agreedBreakEndAt", null);
			assertError(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE");
		}

		@Test
		@DisplayName("좌표가 정상이어도 기존 필수 정보 누락(휴게 한쪽만)은 503")
		void existingCheckWithCoordinates() {
			coordinates("37.123457", "127.987654");
			ReflectionTestUtils.setField(stored, "agreedBreakStartAt", null);

			assertError(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE");
		}

		@Test
		@DisplayName("없는 근무·다른 근로자의 근무는 404 WORK_ASSIGNMENT_NOT_FOUND (좌표 유무와 무관)")
		void notOwned() {
			coordinates("37.123457", "127.987654");
			given(members.findById(6L)).willReturn(Optional.of(member(6L, MemberRole.WORKER)));
			given(assignments.findOwned(6L, ASSIGNMENT_ID)).willReturn(Optional.empty());
			given(assignments.findOwned(WORKER_ID, 999L)).willReturn(Optional.empty());

			assertThatThrownBy(() -> service.find(6L, ASSIGNMENT_ID)).isInstanceOfSatisfying(InsuranceApiException.class,
				error -> assertThat(error.code()).isEqualTo("WORK_ASSIGNMENT_NOT_FOUND"));
			assertThatThrownBy(() -> service.find(WORKER_ID, 999L)).isInstanceOfSatisfying(InsuranceApiException.class,
				error -> assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND));
		}

		@Test
		@DisplayName("시간대가 해석 불가하면 좌표가 있어도 기존 시간대 검증이 유지된다")
		void timeZoneValidationKept() {
			coordinates("37.123457", "127.987654");
			ReflectionTestUtils.setField(stored, "agreedTimeZone", "Mars/Olympus");

			assertThatThrownBy(() -> service.find(WORKER_ID, ASSIGNMENT_ID)).isInstanceOf(InsuranceApiException.class);
		}

		private void assertError(HttpStatus status, String code) {
			assertThatThrownBy(() -> service.find(WORKER_ID, ASSIGNMENT_ID)).isInstanceOfSatisfying(InsuranceApiException.class,
				error -> {
					assertThat(error.status()).isEqualTo(status);
					assertThat(error.code()).isEqualTo(code);
				});
		}
	}

	private void coordinates(String latitude, String longitude) {
		ReflectionTestUtils.setField(stored, "agreedLatitude", latitude == null ? null : new BigDecimal(latitude));
		ReflectionTestUtils.setField(stored, "agreedLongitude", longitude == null ? null : new BigDecimal(longitude));
	}

	private static String snapshot(WorkAssignment assignment) {
		return String.join("|", String.valueOf(assignment.getAgreedLatitude()), String.valueOf(assignment.getAgreedLongitude()),
			assignment.getAgreedWorkLocation(), String.valueOf(assignment.getAgreedStartAt()),
			String.valueOf(assignment.getAgreedDailyWage()), String.valueOf((LocalDateTime) assignment.getConfirmedAtUtc()));
	}
}
