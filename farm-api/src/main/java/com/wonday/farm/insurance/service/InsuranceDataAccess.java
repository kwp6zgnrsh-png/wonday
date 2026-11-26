package com.wonday.farm.insurance.service;

import com.wonday.farm.insurance.exception.InsuranceApiException;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class InsuranceDataAccess {
	private final MemberRepository members;
	public InsuranceDataAccess(MemberRepository members) {
		this.members = members;
	}

	public void requireWorker(long workerId) {
		if (workerId <= 0) throw InsuranceApiException.invalidRequest();
		// 팀 합의: 보험 서버가 동의·철회·연결 대상을 확인하고, 농업 서버는 서버 인증과 원본 소유 관계를 확인한다.
		members.findById(workerId).filter(member -> member.memberRole().orElse(null) == MemberRole.WORKER)
			.orElseThrow(() -> new InsuranceApiException(HttpStatus.NOT_FOUND, "WORKER_NOT_FOUND",
				"해당 근로자를 찾을 수 없습니다."));
	}
}
