package com.wonday.insurance.platform;

import com.wonday.insurance.member.MemberRole;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
class SalaryAccountQueryService {

	private final PlatformLinkRepository links;
	private final FarmSalaryAccountClient farm;

	SalaryAccountStatus findMine(long memberId) {
		Snapshot before = snapshot(memberId, false);
		// 원격 호출 동안 DB 트랜잭션·행 잠금을 유지하지 않는다. 저장·리워드 판정도 이 단계에서 하지 않는다.
		SalaryAccountStatus status = farm.find(before.workerId());
		Snapshot after = snapshot(memberId, true);
		if (!before.equals(after)) throw changed();
		return status;
	}

	private Snapshot snapshot(long memberId, boolean afterRemoteCall) {
		var link = links.findSalaryLink(memberId).orElseThrow(() -> afterRemoteCall ? changed() : required());
		if (link.getMemberRole() != MemberRole.WORKER) {
			throw new PlatformVerificationException(HttpStatus.FORBIDDEN,
				"WORKER_ACCOUNT_REQUIRED", "Worker account required");
		}
		if (link.getConsentVersion() == null || link.getConsentVersion().isBlank()) {
			throw afterRemoteCall ? changed() : required();
		}
		// 응답을 기다리는 동안 철회·연결 교체·동의 버전 변경이 있었다면 이전 연결의 결과를 반환하지 않는다.
		return new Snapshot(link.getLinkId(), link.getWorkerId(), link.getConsentVersion());
	}

	private static PlatformVerificationException required() {
		return new PlatformVerificationException(HttpStatus.CONFLICT,
			"PLATFORM_LINK_REQUIRED", "An active verified farm link is required");
	}

	private static PlatformVerificationException changed() {
		return new PlatformVerificationException(HttpStatus.CONFLICT,
			"PLATFORM_LINK_CHANGED", "Farm link or consent changed during the query; please try again");
	}

	private record Snapshot(long linkId, long workerId, String consentVersion) {}
}
