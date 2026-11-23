package com.wonday.farm.member;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class MemberTest {

	@Test
	void storesAccountFieldsAndRoleCode() {
		Member member = new Member("employer", "{bcrypt}hash", "고용주", MemberRole.EMPLOYER);

		assertThat(member.getId()).isNull();
		assertThat(member.getLoginId()).isEqualTo("employer");
		assertThat(member.passwordHash()).isEqualTo("{bcrypt}hash");
		assertThat(member.displayName()).isEqualTo("고용주");
		assertThat(member.memberRole()).contains(MemberRole.EMPLOYER);
		assertThat(ReflectionTestUtils.getField(member, "role")).isEqualTo("EMPLOYER");
	}

	@Test
	void unknownRoleCodeIsEmpty() {
		Member member = new Member("worker", "{bcrypt}hash", "근로자", MemberRole.WORKER);
		ReflectionTestUtils.setField(member, "role", "worker");

		// 대소문자가 다르거나 정의되지 않은 역할 코드는 허용하지 않는다.
		assertThat(member.memberRole()).isEmpty();
	}

	@Test
	void jpaConstructorCreatesEmptyEntity() {
		Member member = new Member();

		assertThat(member.getLoginId()).isNull();
		assertThat(member.memberRole()).isEmpty();
	}
}
