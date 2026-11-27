package com.wonday.insurance.platform;

import com.wonday.insurance.member.MemberEnrollmentService;
import com.wonday.insurance.platform.FarmPlatformClient.VerifiedWorker;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
class PlatformAccountWriter {

	private final MemberEnrollmentService members;
	private final PlatformLinkService linking;
	private final PlatformLinkRepository links;
	private final FarmPlatformProperties properties;
	private final Clock clock;

	@Transactional
	public PlatformLinkStatus save(long memberId, WorkerIdentity identity, VerifiedWorker worker) {
		// 기존 가입과 같은 회원 → 연결 순서를 사용한다. 연락처·암호문·연결이 모두 성공해야 커밋한다.
		members.lockWorkerAndSaveContact(memberId, identity, worker.phone());
		LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
		linking.link(memberId, worker.workerId(), now);
		return PlatformLinkStatus.from(links.findByMemberIdAndRevokedAtIsNull(memberId).orElseThrow(), properties.consentVersion());
	}
}
