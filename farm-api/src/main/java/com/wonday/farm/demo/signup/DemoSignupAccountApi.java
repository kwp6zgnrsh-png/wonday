package com.wonday.farm.demo.signup;

import com.wonday.farm.auth.FarmPrincipal;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.salary.repository.MemberSalaryAccountRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// 계좌 관리 API를 대신하지 않는 데모 전용 읽기 경로다. 요청으로 회원 ID나 보험 서버 토큰을 받지 않는다.
@Profile("farm-demo")
@RestController
public class DemoSignupAccountApi {
	private final MemberRepository members;
	private final MemberSalaryAccountRepository accounts;

	public DemoSignupAccountApi(MemberRepository members, MemberSalaryAccountRepository accounts) {
		this.members = members;
		this.accounts = accounts;
	}

	@GetMapping(value = "/demo/api/signup/salary-account", produces = "application/json;charset=UTF-8")
	@Transactional(readOnly = true)
	public ResponseEntity<AccountPreview> account(@AuthenticationPrincipal FarmPrincipal principal) {
		// 로그인 이후 계정이 삭제되거나 역할이 유효하지 않게 된 경우도 확인한다.
		members.findById(principal.memberId()).filter(member -> member.memberRole().isPresent())
			.orElseThrow(() -> new AccessDeniedException("Farm account unavailable"));
		var body = accounts.findById(principal.memberId())
			.map(account -> new AccountPreview(principal.memberId(), true, account.bankCode(), account.accountHolderName()))
			.orElseGet(() -> new AccountPreview(principal.memberId(), false, null, null));
		// 엔티티 대신 허용한 필드만 반환한다. 암호문·복호화 번호·키는 보내지 않는다.
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
	}

	@ExceptionHandler(AccessDeniedException.class)
	ResponseEntity<ErrorResponse> forbidden() {
		return error(HttpStatus.FORBIDDEN, "DEMO_ACCESS_DENIED", "현재 로그인 계정의 계좌를 확인할 수 없습니다.");
	}

	@ExceptionHandler({DataAccessResourceFailureException.class, TransientDataAccessException.class,
		CannotCreateTransactionException.class})
	ResponseEntity<ErrorResponse> unavailable() {
		return error(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "현재 계좌 등록 여부를 확인할 수 없습니다.");
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ErrorResponse> unexpected() {
		return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR", "계좌 확인 중 내부 오류가 발생했습니다.");
	}

	private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message) {
		return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new ErrorResponse(code, message));
	}

	public record AccountPreview(long memberId, boolean registered, String bankCode, String accountHolderName) {}
	record ErrorResponse(String code, String message) {}
}
