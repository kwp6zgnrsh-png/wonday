package com.wonday.insurance.platform;

import com.wonday.insurance.member.MemberRole;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SalaryAccountQueryService {

	private final PlatformLinkRepository links;
	private final FarmSalaryAccountClient farm;

	SalaryAccountStatus findMine(long memberId) {
		Observation observation = observe(memberId);
		if (observation.failure() != null) throw observation.failure();
		return observation.account();
	}

	public Observation observe(long memberId) {
		Snapshot before = snapshot(memberId, false);
		SalaryAccountStatus status = null;
		PlatformVerificationException failure = null;
		// 실패도 동의·연결을 재확인한 근거와 함께 돌려준다. 예상하지 못한 코드 오류는 판정 결과로 바꾸지 않는다.
		try {
			status = farm.find(before.workerId());
		} catch (PlatformVerificationException exception) {
			if (exception.getStatus() != HttpStatus.NOT_FOUND && exception.getStatus() != HttpStatus.BAD_GATEWAY
				&& exception.getStatus() != HttpStatus.SERVICE_UNAVAILABLE) throw exception;
			failure = exception;
		}
		Snapshot after = snapshot(memberId, true);
		if (!before.equals(after)) throw changed();
		return new Observation(before, status, failure);
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void lockVerifiedLink(long memberId, Snapshot source) {
		var link = links.lockSalaryLink(memberId, source.linkId()).orElseThrow(SalaryAccountQueryService::changed);
		if (link.getWorkerId() != source.workerId() || !link.getConsentVersion().equals(source.consentVersion())) throw changed();
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

	// 내부 저장 근거다. 사용자 API에는 계좌번호·외부 근로자 ID·이 예외 객체를 반환하지 않는다.
	public record Snapshot(long linkId, long workerId, String consentVersion) {}
	public record Observation(Snapshot source, SalaryAccountStatus account, PlatformVerificationException failure) {
		public Observation {
			if (source == null || (account == null) == (failure == null)) throw new IllegalArgumentException("Invalid salary observation");
		}
		@Override public String toString() { return "SalaryAccountObservation[redacted]"; }
	}
}
