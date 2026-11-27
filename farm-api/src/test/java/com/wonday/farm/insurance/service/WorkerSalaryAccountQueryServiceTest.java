package com.wonday.farm.insurance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import com.wonday.farm.insurance.dto.WorkerSalaryAccountResponse;
import com.wonday.farm.insurance.exception.InsuranceApiException;
import com.wonday.farm.insurance.repository.WorkerSalaryAccountRepository;
import com.wonday.farm.insurance.repository.WorkerSalaryAccountRepository.AccountStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

// WorkerSalaryAccountRepository는 mock이다. 반환 projection을 바꿔 순차 확인할 뿐이며
// 실제 MySQL LEFT JOIN·projection 타입 변환·변경 후 조회·동시성은 검증하지 않는다.
@DisplayName("보험용 급여 계좌 등록 상태 조회 서비스 (Repository mock)")
class WorkerSalaryAccountQueryServiceTest {
	private static final long ID = 101L;

	private WorkerSalaryAccountRepository repository;
	private WorkerSalaryAccountQueryService service;

	@BeforeEach
	void setUp() {
		repository = mock(WorkerSalaryAccountRepository.class);
		service = new WorkerSalaryAccountQueryService(repository);
	}

	static AccountStatus row(long workerId, String role, Long accountMemberId, String bankCode) {
		return new AccountStatus() {
			@Override public long getWorkerId() { return workerId; }
			@Override public String getMemberRole() { return role; }
			@Override public Long getAccountMemberId() { return accountMemberId; }
			@Override public String getBankCode() { return bankCode; }
		};
	}

	private void returns(AccountStatus status) {
		given(repository.findStatus(ID)).willReturn(Optional.ofNullable(status));
	}

	private InsuranceApiException failure(long id) {
		Throwable thrown = catchThrowable(() -> service.find(id));
		assertThat(thrown).isInstanceOf(InsuranceApiException.class);
		return (InsuranceApiException) thrown;
	}

	private static void assertError(InsuranceApiException exception, HttpStatus status, String code) {
		assertThat(exception.status()).isEqualTo(status);
		assertThat(exception.code()).isEqualTo(code);
	}

	@Nested
	@DisplayName("정상")
	class Success {
		@Test
		@DisplayName("등록 근로자: registered=true와 은행 코드, 요청 ID 그대로 Repository에 한 번 전달")
		void registered() {
			returns(row(ID, "WORKER", ID, "020"));
			assertThat(service.find(ID)).isEqualTo(new WorkerSalaryAccountResponse(ID, true, "020"));
			verify(repository).findStatus(ID);
			verifyNoMoreInteractions(repository);
		}

		@Test
		@DisplayName("미등록 근로자: registered=false, bankCode=null")
		void unregistered() {
			returns(row(ID, "WORKER", null, null));
			assertThat(service.find(ID)).isEqualTo(new WorkerSalaryAccountResponse(ID, false, null));
		}

		@ParameterizedTest(name = "{0}")
		@ValueSource(strings = {"002", "004", "000", "999"})
		@DisplayName("은행 코드는 앞자리 0을 포함해 문자열 그대로")
		void leadingZero(String bank) {
			returns(row(ID, "WORKER", ID, bank));
			assertThat(service.find(ID).bankCode()).isEqualTo(bank);
		}

		@Test
		@DisplayName("Long 최대 ID도 그대로 조회")
		void maxId() {
			given(repository.findStatus(Long.MAX_VALUE)).willReturn(Optional.of(row(Long.MAX_VALUE, "WORKER", null, null)));
			assertThat(service.find(Long.MAX_VALUE).workerId()).isEqualTo(Long.MAX_VALUE);
		}

		@Test
		@DisplayName("등록 → 다른 은행 교체 → 해제: mock 응답을 바꾸면 결과가 따라 바뀐다 (실제 DB 반영 검증 아님)")
		void sequence() {
			returns(row(ID, "WORKER", ID, "020"));
			assertThat(service.find(ID)).isEqualTo(new WorkerSalaryAccountResponse(ID, true, "020"));
			returns(row(ID, "WORKER", ID, "088"));
			assertThat(service.find(ID)).isEqualTo(new WorkerSalaryAccountResponse(ID, true, "088"));
			returns(row(ID, "WORKER", null, null));
			assertThat(service.find(ID)).isEqualTo(new WorkerSalaryAccountResponse(ID, false, null));
		}
	}

	@Nested
	@DisplayName("400·404")
	class Rejected {
		@ParameterizedTest(name = "id={0}")
		@ValueSource(longs = {0, -1, Long.MIN_VALUE})
		@DisplayName("ID 0·음수는 400 INVALID_REQUEST, Repository 미호출")
		void invalidId(long id) {
			assertError(failure(id), HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
			verifyNoInteractions(repository);
		}

		@Test
		@DisplayName("회원 없음은 404 WORKER_NOT_FOUND")
		void missing() {
			returns(null);
			assertError(failure(ID), HttpStatus.NOT_FOUND, "WORKER_NOT_FOUND");
		}

		static Stream<Arguments> nonWorkers() {
			return Stream.of(
				Arguments.of("고용주 + 계좌 있음", row(ID, "EMPLOYER", ID, "020")),
				Arguments.of("고용주 + 계좌 없음", row(ID, "EMPLOYER", null, null)),
				Arguments.of("소문자 worker", row(ID, "worker", ID, "020")),
				Arguments.of("앞뒤 공백", row(ID, " WORKER", null, null)),
				Arguments.of("알 수 없는 역할", row(ID, "ADMIN", ID, "020")),
				Arguments.of("역할 null", row(ID, null, null, null)),
				// 역할이 근로자가 아니면 결과 불일치보다 404가 먼저다 (존재 여부 외 정보 비노출)
				Arguments.of("고용주 + 행 ID 불일치", row(999L, "EMPLOYER", 999L, "1")));
		}

		@ParameterizedTest(name = "{0}")
		@MethodSource("nonWorkers")
		@DisplayName("근로자가 아니면 계좌가 있어도 404 WORKER_NOT_FOUND")
		void notWorker(String label, AccountStatus status) {
			returns(status);
			assertError(failure(ID), HttpStatus.NOT_FOUND, "WORKER_NOT_FOUND");
		}

		@Test
		@DisplayName("404 메시지는 고용주·없음 구분 없이 같다")
		void sameMessage() {
			returns(null);
			String missing = failure(ID).getMessage();
			returns(row(ID, "EMPLOYER", ID, "020"));
			assertThat(failure(ID).getMessage()).isEqualTo(missing).doesNotContain("EMPLOYER", "020", "101");
		}
	}

	@Nested
	@DisplayName("비정상 조회 결과·예외 — 503·전파")
	class Inconsistent {
		static Stream<Arguments> rows() {
			return Stream.of(
				Arguments.of("조회 행 workerId 불일치(미등록)", row(102L, "WORKER", null, null)),
				Arguments.of("조회 행 workerId 불일치(등록)", row(102L, "WORKER", 102L, "020")),
				Arguments.of("계좌 회원 ID 불일치", row(ID, "WORKER", 102L, "020")),
				Arguments.of("계좌 ID 없음 + 은행 코드 있음", row(ID, "WORKER", null, "020")),
				Arguments.of("계좌 있음 + 은행 null", row(ID, "WORKER", ID, null)),
				Arguments.of("은행 2자리", row(ID, "WORKER", ID, "20")),
				Arguments.of("은행 4자리", row(ID, "WORKER", ID, "0200")),
				Arguments.of("은행 문자", row(ID, "WORKER", ID, "0a0")),
				Arguments.of("은행 공백 포함", row(ID, "WORKER", ID, "020 ")),
				Arguments.of("은행 전각", row(ID, "WORKER", ID, "０２０")),
				Arguments.of("은행 빈 값", row(ID, "WORKER", ID, "")));
		}

		@ParameterizedTest(name = "{0}")
		@MethodSource("rows")
		@DisplayName("일관되지 않은 결과는 성공·미등록이 아니라 503 SERVICE_UNAVAILABLE")
		void unavailable(String label, AccountStatus status) {
			returns(status);
			assertError(failure(ID), HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE");
		}

		@Test
		@DisplayName("Repository 예외는 그대로 전파된다 (성공·미등록으로 바뀌지 않음)")
		void repositoryException() {
			var failure = new DataAccessResourceFailureException("db down");
			given(repository.findStatus(anyLong())).willThrow(failure);
			assertThat(catchThrowable(() -> service.find(ID))).isSameAs(failure);
		}
	}

	@Nested
	@DisplayName("구조·선언 (정적 확인)")
	class Structure {
		@Test
		@DisplayName("서비스 의존성은 조회 Repository 하나뿐 (SalaryAccountService·SalaryAccountCrypto·쓰기 Repository 없음), readOnly 트랜잭션 선언")
		void dependencies() throws Exception {
			List<Class<?>> types = Arrays.stream(WorkerSalaryAccountQueryService.class.getDeclaredFields())
				.<Class<?>>map(f -> f.getType()).filter(t -> !t.isPrimitive()).toList();
			assertThat(types).containsExactly(WorkerSalaryAccountRepository.class);
			Method find = WorkerSalaryAccountQueryService.class.getMethod("find", long.class);
			assertThat(find.getAnnotation(Transactional.class).readOnly()).isTrue();
		}

		@Test
		@DisplayName("Repository는 쓰기 메서드가 없는 Repository이고, 메서드는 findStatus 하나 (저장·삭제 불가)")
		void readOnlyRepository() {
			assertThat(Repository.class).isAssignableFrom(WorkerSalaryAccountRepository.class);
			assertThat(org.springframework.data.repository.CrudRepository.class.isAssignableFrom(WorkerSalaryAccountRepository.class)).isFalse();
			assertThat(Arrays.stream(WorkerSalaryAccountRepository.class.getDeclaredMethods()).map(Method::getName)).containsExactly("findStatus");
			assertThat(Arrays.stream(AccountStatus.class.getDeclaredMethods()).map(Method::getName))
				.containsExactlyInAnyOrder("getWorkerId", "getMemberRole", "getAccountMemberId", "getBankCode");
		}

		@Test
		@DisplayName("쿼리 문자열: member LEFT JOIN 계좌, 네 별칭만 SELECT, 암호문·예금주·식별정보·전화·버전 컬럼 없음 (실제 SQL 실행은 미검증)")
		void queryShape() throws Exception {
			Query query = WorkerSalaryAccountRepository.class.getMethod("findStatus", long.class).getAnnotation(Query.class);
			assertThat(query.nativeQuery()).isTrue();
			String sql = query.value().replaceAll("\\s+", " ").trim().toLowerCase();
			assertThat(sql).contains("from member m left join member_salary_account account on account.member_id = m.id",
				"where m.id = :workerid");
			String select = sql.substring(sql.indexOf("select ") + 7, sql.indexOf(" from "));
			assertThat(Arrays.stream(select.split(",")).map(String::trim))
				.containsExactly("m.id as workerid", "m.role as memberrole", "account.member_id as accountmemberid",
					"account.bank_code as bankcode");
			assertThat(sql).doesNotContain("ciphertext", "account_holder", "identity", "resident", "phone", "password",
				"salary_account_version", "*", "inner join");
		}

		@Test
		@DisplayName("응답 JSON은 workerId(숫자)·registered(boolean)·bankCode(문자열/null) 세 필드, 미등록도 bankCode:null, toString은 가림")
		void responseJson() throws Exception {
			JsonMapper json = JsonMapper.builder().build();
			var registered = json.readTree(json.writeValueAsString(new WorkerSalaryAccountResponse(101L, true, "020")));
			assertThat(registered.propertyNames()).containsExactlyInAnyOrder("workerId", "registered", "bankCode");
			assertThat(registered.get("workerId").isIntegralNumber()).isTrue();
			assertThat(registered.get("registered").isBoolean()).isTrue();
			assertThat(registered.get("bankCode").isString()).isTrue();
			var empty = json.readTree(json.writeValueAsString(new WorkerSalaryAccountResponse(101L, false, null)));
			assertThat(empty.propertyNames()).containsExactlyInAnyOrder("workerId", "registered", "bankCode");
			assertThat(empty.get("bankCode").isNull()).isTrue();
			assertThat(new WorkerSalaryAccountResponse(101L, true, "020").toString()).isEqualTo("WorkerSalaryAccountResponse[redacted]");
		}
	}
}
