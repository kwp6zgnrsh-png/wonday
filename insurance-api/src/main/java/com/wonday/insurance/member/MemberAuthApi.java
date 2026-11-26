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
		Member member = members.register(request.loginId(), request.name(), request.password(), request.nationalityType());
		return response(member);
	}

	@GetMapping("/csrf")
	CsrfResponse csrf(CsrfToken csrfToken) {
		return new CsrfResponse(csrfToken.getHeaderName(), csrfToken.getToken());
	}

	@GetMapping("/me")
	MemberResponse me(@AuthenticationPrincipal MemberPrincipal principal) {
		// 세션에는 인증·회원 ID를 유지하고, 새 회원 속성은 DB에서 읽어 기존 로그인 세션도 사용할 수 있게 한다.
		return response(members.current(principal.memberId()));
	}

	private static MemberResponse response(Member member) {
		return new MemberResponse(member.getId(), member.getName(), member.getRole(), member.getNationalityType());
	}

	record SignupRequest(
		@NotBlank @Size(max = 100) String loginId,
		@NotBlank @Size(max = 100) String name,
		@NotBlank @Size(min = 8, max = 72) String password,
		NationalityType nationalityType
	) {
		SignupRequest {
			// 기존 3개 필드 요청은 내국인 가입으로 유지한다. 잘못된 enum 값은 역직렬화 단계에서 거부된다.
			nationalityType = nationalityType == null ? NationalityType.DOMESTIC : nationalityType;
		}
	}

	record CsrfResponse(String headerName, String token) {}

	record MemberResponse(long id, String name, MemberRole role, NationalityType nationalityType) {}
}
