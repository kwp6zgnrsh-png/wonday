package com.wonday.farm.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.Optional;

import com.wonday.farm.member.Member;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.util.ReflectionTestUtils;

class FarmUserDetailsServiceTest {
	private final MemberRepository members = mock(MemberRepository.class);
	private final FarmUserDetailsService service = new FarmUserDetailsService(members);

	@Test
	void convertsMemberToPrincipalWithRoleAuthority() {
		Member member = member(7L, "farmer", MemberRole.WORKER);
		given(members.findByLoginId("farmer")).willReturn(Optional.of(member));

		UserDetails details = service.loadUserByUsername("farmer");

		assertThat(details).isInstanceOf(FarmPrincipal.class);
		FarmPrincipal principal = (FarmPrincipal) details;
		assertThat(principal.memberId()).isEqualTo(7L);
		assertThat(principal.getUsername()).isEqualTo("farmer");
		assertThat(principal.getPassword()).isEqualTo("{bcrypt}hash");
		assertThat(principal.displayName()).isEqualTo("농부");
		assertThat(principal.role()).isEqualTo(MemberRole.WORKER);
		assertThat(principal.getAuthorities()).extracting(GrantedAuthority::getAuthority)
			.containsExactly("ROLE_WORKER");
	}

	@Test
	void rejectsUnknownLoginId() {
		given(members.findByLoginId("nobody")).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.loadUserByUsername("nobody"))
			.isInstanceOf(UsernameNotFoundException.class);
	}

	@Test
	void rejectsMemberWithUnsupportedRoleCode() {
		// DB에 허용되지 않은 역할 코드가 있으면 계정이 없는 것과 똑같이 인증을 거부한다.
		Member member = member(8L, "admin", MemberRole.EMPLOYER);
		ReflectionTestUtils.setField(member, "role", "ADMIN");
		given(members.findByLoginId("admin")).willReturn(Optional.of(member));

		assertThatThrownBy(() -> service.loadUserByUsername("admin"))
			.isInstanceOf(UsernameNotFoundException.class);
	}

	private static Member member(long id, String loginId, MemberRole role) {
		Member member = new Member(loginId, "{bcrypt}hash", "농부", role);
		ReflectionTestUtils.setField(member, "id", id);
		return member;
	}
}
