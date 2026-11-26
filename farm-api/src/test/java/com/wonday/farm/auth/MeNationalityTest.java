package com.wonday.farm.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import com.wonday.farm.member.Member;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import com.wonday.farm.member.identity.IdentityCrypto;
import com.wonday.farm.member.identity.NationalityType;
import com.wonday.farm.member.identity.testsupport.IdentityTestKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// 로그인 시 DB 국적 → FarmPrincipal → /me 응답 연결. 로그인 필터·세션 저장·Security 권한 검사는 통합 테스트 영역이다.
// /me는 standalone MockMvc에 principal을 SecurityContext로 직접 넣어 JSON 형태만 확인한다.
@DisplayName("로그인 국적 연계와 /me nationalityType")
class MeNationalityTest {
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private final MemberRepository members = mock(MemberRepository.class);
	private final FarmUserDetailsService details = new FarmUserDetailsService(members);
	private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AuthApi())
		.setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();

	@AfterEach
	void clear() {
		SecurityContextHolder.clearContext();
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(NationalityType.class)
	@DisplayName("DB에 연결된 국적을 FarmPrincipal에 담고 /me가 id·name·role·nationalityType으로 반환한다")
	void storedNationality(NationalityType nationality) throws Exception {
		Member member = member(7L, MemberRole.WORKER);
		IdentityCrypto crypto = IdentityTestKeys.crypto();
		member.assignIdentity(crypto.protect(7L, nationality, IdentityTestKeys.NUMBER));
		given(members.findByLoginId("worker")).willReturn(Optional.of(member));

		FarmPrincipal principal = (FarmPrincipal) details.loadUserByUsername("worker");
		assertThat(principal.nationalityType()).isEqualTo(nationality);

		JsonNode me = me(principal);
		assertThat(me.propertyNames()).containsExactly("id", "name", "role", "nationalityType");
		assertThat(me.get("id").asLong()).isEqualTo(7L);
		assertThat(me.get("name").asString()).isEqualTo("근로자");
		assertThat(me.get("role").asString()).isEqualTo("WORKER");
		assertThat(me.get("nationalityType").asString()).isEqualTo(nationality.name());
		assertThat(me.toString()).doesNotContain("hash", IdentityTestKeys.NUMBER, "worker\"", "identity", "account");
	}

	@Test
	@DisplayName("국적 미등록(기존 미이관) 계정은 nationalityType이 명시적 null이고 식별번호로 추정하지 않는다")
	void legacyNull() throws Exception {
		Member member = member(8L, MemberRole.EMPLOYER);
		given(members.findByLoginId("employer")).willReturn(Optional.of(member));

		FarmPrincipal principal = (FarmPrincipal) details.loadUserByUsername("employer");
		JsonNode me = me(principal);

		assertThat(principal.nationalityType()).isNull();
		assertThat(me.has("nationalityType")).isTrue();
		assertThat(me.get("nationalityType").isNull()).isTrue();
		assertThat(me.get("role").asString()).isEqualTo("EMPLOYER");
	}

	@Test
	@DisplayName("DB의 알 수 없는 국적 문자열은 null로 읽고 로그인 자체는 막지 않는다")
	void unknownNationalityIsNull() {
		Member member = member(9L, MemberRole.WORKER);
		ReflectionTestUtils.setField(member, "nationalityType", "domestic");
		given(members.findByLoginId("worker")).willReturn(Optional.of(member));

		FarmPrincipal principal = (FarmPrincipal) details.loadUserByUsername("worker");
		assertThat(principal.nationalityType()).isNull();
		assertThat(principal.role()).isEqualTo(MemberRole.WORKER);
	}

	@Test
	@DisplayName("기존 5인자 생성자도 호환되며 국적은 null")
	void legacyConstructor() {
		var principal = new FarmPrincipal(3L, "employer", "{bcrypt}hash", "고용주", MemberRole.EMPLOYER);

		assertThat(principal.nationalityType()).isNull();
		assertThat(principal.getAuthorities()).extracting(a -> a.getAuthority()).containsExactly("ROLE_EMPLOYER");
	}

	@Test
	@DisplayName("요청 파라미터의 memberId·role·nationalityType으로 /me 값을 바꾸지 못한다")
	void parametersIgnored() throws Exception {
		var principal = new FarmPrincipal(3L, "employer", "{bcrypt}hash", "고용주", MemberRole.EMPLOYER, NationalityType.DOMESTIC);
		SecurityContextHolder.getContext().setAuthentication(
			UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));

		String raw = mvc.perform(get("/api/auth/me").param("memberId", "99").param("id", "99").param("role", "WORKER")
			.param("nationalityType", "FOREIGN")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		JsonNode me = JSON.readTree(raw);

		assertThat(me.get("id").asLong()).isEqualTo(3L);
		assertThat(me.get("role").asString()).isEqualTo("EMPLOYER");
		assertThat(me.get("nationalityType").asString()).isEqualTo("DOMESTIC");
	}

	private JsonNode me(FarmPrincipal principal) throws Exception {
		SecurityContextHolder.getContext().setAuthentication(
			UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
		var response = mvc.perform(get("/api/auth/me")).andReturn().getResponse();
		assertThat(response.getStatus()).isEqualTo(200);
		return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
	}

	private static Member member(long id, MemberRole role) {
		Member member = new Member(role == MemberRole.WORKER ? "worker" : "employer", "{bcrypt}hash",
			role == MemberRole.WORKER ? "근로자" : "고용주", "01012345678", role);
		ReflectionTestUtils.setField(member, "id", id);
		return member;
	}

}
