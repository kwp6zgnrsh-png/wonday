package com.wonday.farm.insurance.api;

import com.wonday.farm.insurance.dto.WorkerSalaryAccountResponse;
import com.wonday.farm.insurance.service.WorkerSalaryAccountQueryService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 기존 내부 토큰 보안 체인과 InsuranceErrorHandler를 사용한다.
@RestController
@RequestMapping(value = "/api/internal/insurance", produces = "application/json;charset=UTF-8")
public class InsuranceSalaryAccountApi {
	private final WorkerSalaryAccountQueryService accounts;

	public InsuranceSalaryAccountApi(WorkerSalaryAccountQueryService accounts) {
		this.accounts = accounts;
	}

	@GetMapping("/workers/{workerId}/salary-account")
	public ResponseEntity<WorkerSalaryAccountResponse> find(@PathVariable("workerId") long workerId) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(accounts.find(workerId));
	}
}
