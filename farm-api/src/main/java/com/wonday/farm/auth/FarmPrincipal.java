package com.wonday.farm.auth;

import java.util.List;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

// Spring Security의 인증 사용자 정보에 농업 회원 ID, 표시 이름, 역할을 함께 보관한다.
public class FarmPrincipal extends User {
	private final long memberId;
	private final String displayName;
	private final MemberRole role;

	FarmPrincipal(long memberId, String loginId, String passwordHash, String displayName, MemberRole role) {
		// DB 역할을 ROLE_EMPLOYER, ROLE_WORKER처럼 변환해 hasAnyRole 권한 검사에 사용한다.
		super(loginId, passwordHash, List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
		this.memberId = memberId;
		this.displayName = displayName;
		this.role = role;
	}

	public long memberId() {
		return memberId;
	}

	public String displayName() {
		return displayName;
	}

	public MemberRole role() {
		return role;
	}
}
