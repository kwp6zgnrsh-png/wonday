package com.wonday.insurance.member;

import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

public final class MemberPrincipal extends User {

	private final long memberId;
	private final String name;
	private final MemberRole role;

	MemberPrincipal(Member member) {
		super(member.getLoginId(), member.getPasswordHash(),
			List.of(new SimpleGrantedAuthority("ROLE_" + member.getRole().name())));
		this.memberId = member.getId();
		this.name = member.getName();
		this.role = member.getRole();
	}

	public long memberId() {
		return memberId;
	}

	String name() {
		return name;
	}

	MemberRole role() {
		return role;
	}
}
