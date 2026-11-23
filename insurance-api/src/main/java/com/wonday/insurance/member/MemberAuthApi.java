package com.wonday.insurance.member;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
class MemberAuthApi {

	private final MemberService members;

	@PostMapping("/signup")
	@ResponseStatus(HttpStatus.CREATED)
	MemberResponse signup(@Valid @RequestBody SignupRequest request) {
		Member member = members.register(request.loginId(), request.name(), request.password());
		return new MemberResponse(member.getId(), member.getName(), member.getRole());
	}

	@GetMapping("/csrf")
	CsrfResponse csrf(CsrfToken csrfToken) {
		return new CsrfResponse(csrfToken.getHeaderName(), csrfToken.getToken());
	}

	@GetMapping("/me")
	MemberResponse me(@AuthenticationPrincipal MemberPrincipal principal) {
		return new MemberResponse(principal.memberId(), principal.name(), principal.role());
	}

	record SignupRequest(
		@NotBlank @Size(max = 100) String loginId,
		@NotBlank @Size(max = 100) String name,
		@NotBlank @Size(min = 8, max = 72) String password
	) {}

	record CsrfResponse(String headerName, String token) {}

	record MemberResponse(long id, String name, MemberRole role) {}
}
