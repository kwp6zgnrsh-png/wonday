package com.wonday.farm.auth;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 로그인과 로그아웃은 SecurityConfig의 필터가 처리하고, 여기서는 CSRF 토큰과 본인 정보를 제공한다.
@RestController
@RequestMapping("/api/auth")
public class AuthApi {
	@GetMapping("/csrf")
	public CsrfResponse csrf(CsrfToken csrfToken) {
		// 브라우저는 로그인이나 로그아웃을 요청할 때 이 이름과 값으로 요청 헤더를 만든다.
		return new CsrfResponse(csrfToken.getHeaderName(), csrfToken.getToken());
	}

	// Windows PowerShell 5.1에서 한글 이름을 올바르게 읽도록. 응답에 UTF-8을 명시한다
	@GetMapping(value = "/me", produces = "application/json;charset=UTF-8")
	public MemberResponse me(@AuthenticationPrincipal FarmPrincipal member) {
		// 로그인 세션의 사용자로 본인을 식별하며, 응답은 회원 ID·이름·역할로 제한한다.
		return new MemberResponse(member.memberId(), member.displayName(), member.role());
	}

	record CsrfResponse(String headerName, String token) {}

	record MemberResponse(long id, String name, MemberRole role) {}
}
