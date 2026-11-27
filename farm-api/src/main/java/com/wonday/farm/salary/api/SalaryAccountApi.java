package com.wonday.farm.salary.api;

import com.wonday.farm.auth.FarmPrincipal;
import com.wonday.farm.member.MemberRole;
import com.wonday.farm.salary.dto.SalaryAccountRequest;
import com.wonday.farm.salary.dto.SalaryAccountResponse;
import com.wonday.farm.salary.exception.SalaryAccountException;
import com.wonday.farm.salary.service.SalaryAccountService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/me/salary-account", produces = "application/json;charset=UTF-8")
public class SalaryAccountApi {
	private final SalaryAccountService accounts;

	public SalaryAccountApi(SalaryAccountService accounts) { this.accounts = accounts; }

	@GetMapping
	public ResponseEntity<SalaryAccountResponse> findMine(@AuthenticationPrincipal FarmPrincipal principal) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(accounts.findMine(workerId(principal)));
	}

	@PutMapping(consumes = "application/json")
	public ResponseEntity<SalaryAccountResponse> replace(@AuthenticationPrincipal FarmPrincipal principal,
			@Valid @RequestBody SalaryAccountRequest request) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(accounts.replace(workerId(principal), request));
	}

	@DeleteMapping
	public ResponseEntity<Void> remove(@AuthenticationPrincipal FarmPrincipal principal, @RequestParam String version) {
		long memberId = workerId(principal);
		// DELETE는 본문 없이 조회 당시 버전을 쿼리로 전달한다. 중복 값·소수·음수도 거절한다.
		if (version == null || !version.matches("[0-9]{1,19}")) throw SalaryAccountException.invalid();
		long expectedVersion;
		try {
			expectedVersion = Long.parseLong(version);
		} catch (NumberFormatException exception) {
			throw SalaryAccountException.invalid();
		}
		accounts.remove(memberId, expectedVersion);
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	private long workerId(FarmPrincipal principal) {
		if (principal == null || principal.memberId() <= 0 || principal.role() != MemberRole.WORKER) {
			throw SalaryAccountException.forbidden();
		}
		return principal.memberId();
	}
}
