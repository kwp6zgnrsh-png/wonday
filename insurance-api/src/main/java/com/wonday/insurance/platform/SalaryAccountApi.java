package com.wonday.insurance.platform;

import com.wonday.insurance.member.MemberPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform/salary-account")
@RequiredArgsConstructor
class SalaryAccountApi {

	private final SalaryAccountQueryService accounts;

	@GetMapping
	ResponseEntity<SalaryAccountStatus> findMine(@AuthenticationPrincipal MemberPrincipal member) {
		// 조회 대상 회원·근로자 ID를 요청으로 받지 않고 로그인한 회원의 유효 연결에서 구한다.
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(accounts.findMine(member.memberId()));
	}

	@ExceptionHandler(PlatformVerificationException.class)
	ResponseEntity<QueryError> failed(PlatformVerificationException exception) {
		return ResponseEntity.status(exception.getStatus()).cacheControl(CacheControl.noStore())
			.body(new QueryError(exception.getCode(), exception.getMessage()));
	}

	record QueryError(String code, String message) {}
}
