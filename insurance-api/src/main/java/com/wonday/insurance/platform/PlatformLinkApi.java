package com.wonday.insurance.platform;

import com.wonday.insurance.member.MemberPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/platform/link")
@RequiredArgsConstructor
class PlatformLinkApi {

	private final PlatformAccountService accounts;

	@PostMapping
	ResponseEntity<PlatformLinkStatus> register(@AuthenticationPrincipal MemberPrincipal member,
			@Valid @RequestBody PlatformLinkRequest request) {
		// 회원·외부 근로자 ID는 클라이언트 입력으로 받지 않는다. 인증 세션과 농업 본인 확인 결과를 사용한다.
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(accounts.register(member.memberId(), request));
	}

	@GetMapping
	ResponseEntity<PlatformLinkStatus> status(@AuthenticationPrincipal MemberPrincipal member) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(accounts.status(member.memberId()));
	}

	@ExceptionHandler(PlatformVerificationException.class)
	ResponseEntity<LinkError> failed(PlatformVerificationException exception) {
		return ResponseEntity.status(exception.getStatus()).cacheControl(CacheControl.noStore())
			.body(new LinkError(exception.getCode(), exception.getMessage()));
	}

	record LinkError(String code, String message) {}
}
