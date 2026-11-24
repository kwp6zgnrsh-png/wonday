package com.wonday.insurance.member;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class MemberEnrollmentService {

	private final MemberRepository members;

	@Transactional(propagation = Propagation.MANDATORY)
	public void lockWorkerAndSaveContact(long memberId, String phone, String residentRegistrationNumber) {
		Member member = members.findForEnrollment(memberId)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Member does not exist"));
		if (member.getRole() != MemberRole.WORKER) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Worker account required");
		}
		member.updateEnrollmentContact(phone, residentRegistrationNumber);
	}
}
