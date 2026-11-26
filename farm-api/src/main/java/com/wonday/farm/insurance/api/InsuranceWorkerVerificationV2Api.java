package com.wonday.farm.insurance.api;

import com.wonday.farm.insurance.dto.VerifiedWorkerV2Response;
import com.wonday.farm.insurance.dto.WorkerVerificationV2Request;
import com.wonday.farm.insurance.service.WorkerVerificationV2Service;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 기존 V1 컨트롤러·생성자·응답을 바꾸지 않는 별도 경로다. 기존 내부 토큰 보안 체인을 사용한다.
@RestController
@RequestMapping(value = "/api/internal/insurance/v2", produces = "application/json;charset=UTF-8")
public class InsuranceWorkerVerificationV2Api {
	private final WorkerVerificationV2Service verification;
	public InsuranceWorkerVerificationV2Api(WorkerVerificationV2Service verification) { this.verification = verification; }

	@PostMapping(value = "/worker-verifications", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<VerifiedWorkerV2Response> verify(@Valid @RequestBody WorkerVerificationV2Request request) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(verification.verify(request));
	}
}
