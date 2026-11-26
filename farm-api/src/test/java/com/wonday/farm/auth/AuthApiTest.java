package com.wonday.farm.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.wonday.farm.member.MemberRole;
import org.junit.jupiter.api.Test;
import org.springframework.security.web.csrf.DefaultCsrfToken;

class AuthApiTest {
	private final AuthApi api = new AuthApi();

	@Test
	void csrfReturnsHeaderNameAndToken() {
		var response = api.csrf(new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "token-value"));

		assertThat(response.headerName()).isEqualTo("X-CSRF-TOKEN");
		assertThat(response.token()).isEqualTo("token-value");
	}

	@Test
	void meReturnsIdNameRoleAndNullableNationalityOfPrincipal() {
		var principal = new FarmPrincipal(3L, "employer", "{bcrypt}hash", "고용주", MemberRole.EMPLOYER);

		var response = api.me(principal);

		assertThat(response.id()).isEqualTo(3L);
		assertThat(response.name()).isEqualTo("고용주");
		assertThat(response.role()).isEqualTo(MemberRole.EMPLOYER);
		assertThat(response.nationalityType()).isNull();
		// 응답 레코드에는 로그인 ID와 비밀번호 해시가 들어갈 자리가 없다.
		assertThat(AuthApi.MemberResponse.class.getRecordComponents())
			.extracting(component -> component.getName())
			.containsExactly("id", "name", "role", "nationalityType");
	}
}
