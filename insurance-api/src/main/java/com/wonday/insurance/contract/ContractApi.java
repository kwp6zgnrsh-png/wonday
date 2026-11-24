package com.wonday.insurance.contract;

import com.wonday.insurance.member.MemberPrincipal;
import com.wonday.insurance.platform.PlatformVerificationException;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/contracts")
@RequiredArgsConstructor
class ContractApi {

	private final ContractService contracts;

	@PostMapping
	ResponseEntity<ContractView> enroll(@AuthenticationPrincipal MemberPrincipal member,
		@Valid @RequestBody ContractEnrollmentRequest request) {
		ContractView contract = contracts.enroll(member.memberId(), request);
		return ResponseEntity.created(URI.create("/api/contracts/" + contract.id())).body(contract);
	}

	@GetMapping
	List<ContractView> list(@AuthenticationPrincipal MemberPrincipal member) {
		return contracts.list(member.memberId());
	}

	@GetMapping("/{contractId}")
	ContractView detail(@AuthenticationPrincipal MemberPrincipal member, @PathVariable String contractId) {
		if (!contractId.matches("[1-9][0-9]*")) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid contract ID");
		}
		try {
			return contracts.detail(member.memberId(), Long.parseLong(contractId));
		} catch (NumberFormatException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Contract ID exceeds the supported range");
		}
	}

	@ExceptionHandler(PlatformVerificationException.class)
	ResponseEntity<VerificationError> verificationFailed(PlatformVerificationException exception) {
		return ResponseEntity.status(exception.getStatus())
			.body(new VerificationError(exception.getCode(), exception.getMessage()));
	}

	record VerificationError(String code, String message) {}
}
