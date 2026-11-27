package com.wonday.insurance.member;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class MemberAccessService {

	private final MemberRepository members;

	@Transactional(readOnly = true)
	public void requireAdmin(long memberId) {
		var profile = members.findEnrollmentProfile(memberId)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Member does not exist"));
		if (profile.getRole() != MemberRole.ADMIN) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin account required");
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void lockAdmin(long memberId) {
		var member = members.findForEnrollment(memberId)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Member does not exist"));
		if (member.getRole() != MemberRole.ADMIN) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin account required");
	}

	@Transactional(readOnly = true)
	public void requireMember(long memberId) {
		members.findEnrollmentProfile(memberId)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Member does not exist"));
	}

	@Transactional(readOnly = true)
	public void requireWorker(long memberId) {
		var profile = members.findEnrollmentProfile(memberId)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Member does not exist"));
		requireWorker(profile.getRole());
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void lockWorker(long memberId) {
		// 계약 생성과 같은 회원 → 농업 연결 순서로 잠근다. 외부 조회가 끝난 뒤 저장 경계에서만 사용한다.
		var member = members.findForEnrollment(memberId)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Member does not exist"));
		requireWorker(member.getRole());
	}

	private static void requireWorker(MemberRole role) {
		if (role != MemberRole.WORKER) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Worker account required");
	}
}
