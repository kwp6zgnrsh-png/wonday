package com.wonday.farm.auth;

import java.util.List;

import com.wonday.farm.member.MemberRole;
import com.wonday.farm.member.identity.NationalityType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

// Spring Security의 인증 사용자 정보에 농업 회원 ID, 표시 이름, 역할을 함께 보관한다.
public class FarmPrincipal extends User {
	private final long memberId;
	private final String displayName;
	private final MemberRole role;
	private final NationalityType nationalityType;

	FarmPrincipal(long memberId, String loginId, String passwordHash, String displayName, MemberRole role) {
		this(memberId, loginId, passwordHash, displayName, role, null);
	}

	FarmPrincipal(long memberId, String loginId, String passwordHash, String displayName, MemberRole role,
			NationalityType nationalityType) {
		// DB 역할을 ROLE_EMPLOYER, ROLE_WORKER처럼 변환해 hasAnyRole 권한 검사에 사용한다.
		super(loginId, passwordHash, List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
		this.memberId = memberId;
		this.displayName = displayName;
		this.role = role;
		// 기존 미이관 계정의 국적은 추측하지 않고 null로 유지한다.
		this.nationalityType = nationalityType;
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

	public NationalityType nationalityType() { return nationalityType; }
}
