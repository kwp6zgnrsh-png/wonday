package com.wonday.farm.demo.insurance;

import com.wonday.farm.auth.FarmPrincipal;
import com.wonday.farm.insurance.dto.VerifiedWorkerV2Response;
import com.wonday.farm.insurance.dto.WorkerVerificationV2Request;
import com.wonday.farm.insurance.exception.InsuranceApiException;
import com.wonday.farm.insurance.service.WorkerVerificationV2Service;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

// 실제 내부 API의 Bearer 토큰을 브라우저에 전달하지 않는 본인 확인용 화면이다.
// farm-demo에서만 등록하며 농업 로그인 세션·CSRF와 본인 범위를 별도로 검사한다.
@Profile("farm-demo")
@RestController
public class DemoWorkerVerificationV2Api {
	private final WorkerVerificationV2Service verification;
	private final MemberRepository members;

	public DemoWorkerVerificationV2Api(WorkerVerificationV2Service verification, MemberRepository members) {
		this.verification = verification;
		this.members = members;
	}

	@GetMapping(value = "/demo/worker-verification", produces = "text/html;charset=UTF-8")
	public ResponseEntity<Resource> page() {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
			.body(new ClassPathResource("demo/farm-worker-verification.html"));
	}

	@PostMapping(value = "/demo/api/insurance/v2/worker-verifications",
		consumes = "application/json", produces = "application/json;charset=UTF-8")
	@Transactional(readOnly = true)
	public ResponseEntity<VerifiedWorkerV2Response> verify(
			@AuthenticationPrincipal FarmPrincipal principal,
			@Valid @RequestBody WorkerVerificationV2Request request) {
		// 세션 발급 후 DB 역할이 바뀌었거나 계정이 삭제된 경우에도 접근을 거절한다.
		if (principal == null || principal.role() != MemberRole.WORKER) throw new AccessDeniedException("Worker required");
		members.findById(principal.memberId())
			.filter(member -> member.memberRole().orElse(null) == MemberRole.WORKER)
			.orElseThrow(() -> new AccessDeniedException("Worker required"));
		var result = verification.verify(request);
		// 다른 근로자의 정확한 정보를 입력해도 그 근로자의 ID·정보를 화면으로 조회할 수 없다.
		if (result.workerId() != principal.memberId()) {
			throw new InsuranceApiException(HttpStatus.NOT_FOUND, "WORKER_IDENTITY_NOT_MATCHED",
				"입력한 정보와 일치하는 근로자를 확인할 수 없습니다.");
		}
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
	}

	@ExceptionHandler(InsuranceApiException.class)
	ResponseEntity<ErrorResponse> expected(InsuranceApiException exception) {
		return error(exception.status(), exception.code(), exception.getMessage());
	}

	@ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
	ResponseEntity<ErrorResponse> invalidRequest() {
		// 검증 예외의 원문에는 식별번호가 포함될 수 있어 고정 문구만 반환한다.
		return expected(InsuranceApiException.invalidRequest());
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
