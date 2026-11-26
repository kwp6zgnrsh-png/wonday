package com.wonday.farm.auth;

import com.wonday.farm.member.MemberRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class FarmUserDetailsService implements UserDetailsService {
	private final MemberRepository members;

	FarmUserDetailsService(MemberRepository members) {
		this.members = members;
	}

	@Override
	public UserDetails loadUserByUsername(String loginId) {
		// JPA로 계정을 찾아 인증 정보로 변환하면 Spring Security가 해시를 사용해 입력 비밀번호를 검증한다.
		return members.findByLoginId(loginId)
			.flatMap(member -> member.memberRole().map(role -> new FarmPrincipal(
				member.getId(), member.getLoginId(), member.passwordHash(), member.displayName(), role,
				member.nationalityType().orElse(null))))
			.orElseThrow(() -> new UsernameNotFoundException("Farm account not found"));
	}
}
