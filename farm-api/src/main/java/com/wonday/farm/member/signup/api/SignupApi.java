package com.wonday.farm.member.signup.api;

import com.wonday.farm.member.signup.dto.SignupRequest;
import com.wonday.farm.member.signup.dto.SignupResponse;
import com.wonday.farm.member.signup.service.SignupService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SignupApi {
	private final SignupService signup;
	public SignupApi(SignupService signup) { this.signup = signup; }

	@PostMapping(value = "/api/auth/signup", consumes = MediaType.APPLICATION_JSON_VALUE,
		produces = "application/json;charset=UTF-8")
	public ResponseEntity<SignupResponse> signup(@Valid @RequestBody SignupRequest request) {
		// 세션에 인증 정보를 넣지 않는다. 가입 후 기존 로그인 API를 별도로 호출한다.
		return ResponseEntity.status(HttpStatus.CREATED).body(signup.signup(request));
	}
}
