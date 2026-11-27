package com.wonday.farm.demo.insurance;

import com.wonday.farm.auth.FarmPrincipal;
import com.wonday.farm.insurance.dto.WorkerSalaryAccountResponse;
import com.wonday.farm.insurance.exception.InsuranceApiException;
import com.wonday.farm.insurance.service.WorkerSalaryAccountQueryService;
import com.wonday.farm.member.MemberRole;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// 보험 서버 토큰을 브라우저에 주지 않고, 같은 조회 서비스의 본인 결과만 확인하는 간이 화면이다.
@Profile("farm-demo")
@RestController
public class DemoInsuranceSalaryAccountApi {
	private final WorkerSalaryAccountQueryService accounts;

	public DemoInsuranceSalaryAccountApi(WorkerSalaryAccountQueryService accounts) {
		this.accounts = accounts;
	}

	@GetMapping(value = "/demo/insurance-salary-account", produces = "text/html;charset=UTF-8")
	public ResponseEntity<Resource> page() {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
			.body(new ClassPathResource("demo/farm-insurance-salary-account.html"));
	}

	@GetMapping(value = "/demo/api/insurance/salary-account", produces = "application/json;charset=UTF-8")
	public ResponseEntity<WorkerSalaryAccountResponse> findMine(@AuthenticationPrincipal FarmPrincipal principal) {
		if (principal == null || principal.memberId() <= 0 || principal.role() != MemberRole.WORKER) {
			throw new AccessDeniedException("Worker required");
		}
		// ID는 요청 경로·쿼리에서 받지 않는다. 현재 DB 역할도 실제 보험용 조회 서비스가 확인한다.
		var result = accounts.find(principal.memberId());
		if (result.workerId() != principal.memberId()) throw InsuranceApiException.unavailable();
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
	}

	@ExceptionHandler(InsuranceApiException.class)
	ResponseEntity<ErrorResponse> expected(InsuranceApiException exception) {
		return error(exception.status(), exception.code(), exception.getMessage());
	}

	@ExceptionHandler(AccessDeniedException.class)
	ResponseEntity<ErrorResponse> forbidden() {
		return error(HttpStatus.FORBIDDEN, "DEMO_ACCESS_DENIED", "근로자 본인 계정으로 로그인해 주세요.");
	}

	@ExceptionHandler({DataAccessResourceFailureException.class, TransientDataAccessException.class,
		CannotCreateTransactionException.class, TransactionTimedOutException.class})
	ResponseEntity<ErrorResponse> unavailable() {
		return expected(InsuranceApiException.unavailable());
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ErrorResponse> unexpected() {
		return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR", "서버 내부 오류가 발생했습니다.");
	}

	private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message) {
		return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new ErrorResponse(code, message));
	}

	record ErrorResponse(String code, String message) {}
}
