package com.wonday.farm.member.identity;

import static com.wonday.farm.member.identity.testsupport.IdentityTestKeys.NUMBER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import com.wonday.farm.member.Member;
import com.wonday.farm.member.MemberRole;
import com.wonday.farm.member.identity.testsupport.IdentityTestKeys;
import com.wonday.migration.identity.IdentityMigrationMember;
import jakarta.persistence.Column;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

// StoredIdentity 형식 검사와 Member·IdentityMigrationMember의 식별정보 연결 규칙.
// V8의 NULL·CHECK·UNIQUE 제약이 실제 MySQL에서 적용되는지는 이 테스트로 검증하지 않는다.
@DisplayName("식별정보 저장값과 회원 엔티티 연결")
class MemberIdentityStorageTest {
	private static final String HMAC = "a".repeat(64);
	private final IdentityCrypto crypto = IdentityTestKeys.crypto();

	@Nested
	@DisplayName("StoredIdentity")
	class Stored {
		@Test
		@DisplayName("정상 묶음은 생성되고 toString은 가린다")
		void valid() {
			var stored = new StoredIdentity(1L, NationalityType.FOREIGN, IdentityType.FOREIGN_REGISTRATION_NUMBER, "v1:x", HMAC);

			assertThat(stored.toString()).isEqualTo("StoredIdentity[redacted]").doesNotContain(HMAC);
		}

		@Test
		@DisplayName("회원 ID 0·음수, 국적 null, 종류 null·불일치, 암호문 null·버전 불일치·257자, HMAC null·대문자·길이·비16진수는 거절")
		void invalid() {
			assertInvalid(0L, NationalityType.DOMESTIC, IdentityType.RESIDENT_REGISTRATION_NUMBER, "v1:x", HMAC);
			assertInvalid(-1L, NationalityType.DOMESTIC, IdentityType.RESIDENT_REGISTRATION_NUMBER, "v1:x", HMAC);
			assertInvalid(1L, null, IdentityType.RESIDENT_REGISTRATION_NUMBER, "v1:x", HMAC);
			assertInvalid(1L, NationalityType.DOMESTIC, null, "v1:x", HMAC);
			assertInvalid(1L, NationalityType.DOMESTIC, IdentityType.FOREIGN_REGISTRATION_NUMBER, "v1:x", HMAC);
			assertInvalid(1L, NationalityType.FOREIGN, IdentityType.RESIDENT_REGISTRATION_NUMBER, "v1:x", HMAC);
			assertInvalid(1L, NationalityType.DOMESTIC, IdentityType.RESIDENT_REGISTRATION_NUMBER, null, HMAC);
			assertInvalid(1L, NationalityType.DOMESTIC, IdentityType.RESIDENT_REGISTRATION_NUMBER, "v2:x", HMAC);
			assertInvalid(1L, NationalityType.DOMESTIC, IdentityType.RESIDENT_REGISTRATION_NUMBER, "v1:" + "a".repeat(254), HMAC);
			assertInvalid(1L, NationalityType.DOMESTIC, IdentityType.RESIDENT_REGISTRATION_NUMBER, "v1:x", null);
			assertInvalid(1L, NationalityType.DOMESTIC, IdentityType.RESIDENT_REGISTRATION_NUMBER, "v1:x", "A".repeat(64));
			assertInvalid(1L, NationalityType.DOMESTIC, IdentityType.RESIDENT_REGISTRATION_NUMBER, "v1:x", "a".repeat(63));
			assertInvalid(1L, NationalityType.DOMESTIC, IdentityType.RESIDENT_REGISTRATION_NUMBER, "v1:x", "a".repeat(65));
			assertInvalid(1L, NationalityType.DOMESTIC, IdentityType.RESIDENT_REGISTRATION_NUMBER, "v1:x", "g".repeat(64));
		}

		@Test
		@DisplayName("256자 암호문은 허용 (경계)")
		void lengthBoundary() {
			var stored = new StoredIdentity(1L, NationalityType.DOMESTIC, IdentityType.RESIDENT_REGISTRATION_NUMBER,
				"v1:" + "a".repeat(253), HMAC);
			assertThat(stored.ciphertext()).hasSize(256);
		}

		private void assertInvalid(long id, NationalityType nationality, IdentityType type, String ciphertext, String hmac) {
			assertThatThrownBy(() -> new StoredIdentity(id, nationality, type, ciphertext, hmac))
				.isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining(String.valueOf(hmac));
		}
	}

	@Nested
	@DisplayName("Member.assignIdentity")
	class MemberEntity {
		@Test
		@DisplayName("같은 회원 ID의 식별정보 네 필드를 연결하고 기존 회원 필드(로그인 ID·비밀번호 해시·이름·전화·역할)는 그대로다")
		void assigns() {
			Member member = member(7L);
			assertThat(member.nationalityType()).isEmpty();
			assertThat(member.identityType()).isEmpty();
			StoredIdentity stored = crypto.protect(7L, NationalityType.DOMESTIC, NUMBER);

			member.assignIdentity(stored);

			assertThat(member.nationalityType()).contains(NationalityType.DOMESTIC);
			assertThat(member.identityType()).contains(IdentityType.RESIDENT_REGISTRATION_NUMBER);
			assertThat(ReflectionTestUtils.getField(member, "nationalityType")).isEqualTo("DOMESTIC");
			assertThat(ReflectionTestUtils.getField(member, "identityType")).isEqualTo("RESIDENT_REGISTRATION_NUMBER");
			assertThat(ReflectionTestUtils.getField(member, "identityCiphertext")).isEqualTo(stored.ciphertext());
			assertThat(ReflectionTestUtils.getField(member, "identityHmac")).isEqualTo(stored.hmac());
			assertThat(member.getId()).isEqualTo(7L);
			assertThat(member.getLoginId()).isEqualTo("worker-7");
			assertThat(member.passwordHash()).isEqualTo("$2a$hash");
			assertThat(member.displayName()).isEqualTo("근로자");
			assertThat(member.memberRole()).contains(MemberRole.WORKER);
			assertThat(ReflectionTestUtils.getField(member, "phone")).isEqualTo("01000000000");
		}

		@Test
		@DisplayName("ID 미발급·다른 회원 ID·null 식별정보는 IllegalArgumentException이고 필드를 바꾸지 않는다")
		void wrongTarget() {
			Member unsaved = new Member("worker-x", "$2a$hash", "근로자", MemberRole.WORKER);
			Member member = member(7L);
			StoredIdentity forOther = crypto.protect(8L, NationalityType.DOMESTIC, NUMBER);

			assertThatThrownBy(() -> unsaved.assignIdentity(crypto.protect(7L, NationalityType.DOMESTIC, NUMBER)))
				.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> member.assignIdentity(forOther)).isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> member.assignIdentity(null)).isInstanceOf(IllegalArgumentException.class);
			assertIdentityEmpty(member);
		}

		@ParameterizedTest(name = "이미 있는 필드: {0}")
		@ValueSource(strings = {"nationalityType", "identityType", "identityCiphertext", "identityHmac"})
		@DisplayName("네 필드 중 하나라도 이미 있으면 덮어쓰지 않고 IllegalStateException")
		void noOverwrite(String existingField) {
			Member member = member(7L);
			ReflectionTestUtils.setField(member, existingField, "EXISTING");

			assertThatThrownBy(() -> member.assignIdentity(crypto.protect(7L, NationalityType.DOMESTIC, NUMBER)))
				.isInstanceOf(IllegalStateException.class);
			assertThat(ReflectionTestUtils.getField(member, existingField)).isEqualTo("EXISTING");
			for (String other : List.of("nationalityType", "identityType", "identityCiphertext", "identityHmac")) {
				if (!other.equals(existingField)) assertThat(ReflectionTestUtils.getField(member, other)).isNull();
			}
		}

		@Test
		@DisplayName("두 번째 연결은 거절되고 첫 값이 유지된다")
		void secondAssignRejected() {
			Member member = member(7L);
			StoredIdentity first = crypto.protect(7L, NationalityType.DOMESTIC, NUMBER);
			member.assignIdentity(first);

			assertThatThrownBy(() -> member.assignIdentity(crypto.protect(7L, NationalityType.FOREIGN, NUMBER)))
				.isInstanceOf(IllegalStateException.class);
			assertThat(ReflectionTestUtils.getField(member, "identityCiphertext")).isEqualTo(first.ciphertext());
			assertThat(member.nationalityType()).contains(NationalityType.DOMESTIC);
		}

		@Test
		@DisplayName("DB의 알 수 없는 값은 Optional.empty로 읽는다 (추측하지 않음)")
		void unknownStoredValues() {
			Member member = member(7L);
			ReflectionTestUtils.setField(member, "nationalityType", "domestic");
			ReflectionTestUtils.setField(member, "identityType", "RRN");

			assertThat(member.nationalityType()).isEmpty();
			assertThat(member.identityType()).isEmpty();
		}

		@Test
		@DisplayName("Member에는 주민번호 원문 필드가 매핑되지 않는다 (일반 앱은 원문을 읽지 않음)")
		void noLegacyNumberMapping() {
			assertThat(Arrays.stream(Member.class.getDeclaredFields()).map(Field::getName))
				.noneMatch(name -> name.toLowerCase().contains("resident") || name.toLowerCase().contains("legacy"));
		}
	}

	@Nested
	@DisplayName("IdentityMigrationMember (이관 전용)")
	class MigrationEntity {
		@Test
		@DisplayName("같은 ID 검사·네 필드 연결·덮어쓰기 거절이 Member와 같고, 기존 번호 값은 그대로다")
		void sameRules() {
			IdentityMigrationMember row = migrationRow(7L, "111111-1111111");
			StoredIdentity stored = crypto.protect(7L, NationalityType.DOMESTIC, NUMBER);

			assertThatThrownBy(() -> row.assignIdentity(crypto.protect(8L, NationalityType.DOMESTIC, NUMBER)))
				.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> row.assignIdentity(null)).isInstanceOf(IllegalArgumentException.class);
			assertThat(row.hasAnyStored()).isFalse();

			row.assignIdentity(stored);

			assertThat(row.nationality()).isEqualTo("DOMESTIC");
			assertThat(row.type()).isEqualTo("RESIDENT_REGISTRATION_NUMBER");
			assertThat(row.ciphertext()).isEqualTo(stored.ciphertext());
			assertThat(row.hmac()).isEqualTo(stored.hmac());
			assertThat(row.legacy()).isEqualTo("111111-1111111");
			assertThatThrownBy(() -> row.assignIdentity(crypto.protect(7L, NationalityType.DOMESTIC, NUMBER)))
				.isInstanceOf(IllegalStateException.class);
			assertThat(row.ciphertext()).isEqualTo(stored.ciphertext());
			assertThat(row.toString()).isEqualTo("IdentityMigrationMember[redacted]");
		}

		@Test
		@DisplayName("ID 없는 행은 거절, 일부 필드만 있어도 hasAnyStored=true로 덮어쓰기 거절")
		void partial() {
			IdentityMigrationMember noId = BeanUtils.instantiateClass(IdentityMigrationMember.class);
			assertThatThrownBy(() -> noId.assignIdentity(crypto.protect(7L, NationalityType.DOMESTIC, NUMBER)))
				.isInstanceOf(IllegalArgumentException.class);

			IdentityMigrationMember row = migrationRow(7L, NUMBER);
			ReflectionTestUtils.setField(row, "hmac", HMAC);
			assertThat(row.hasAnyStored()).isTrue();
			assertThatThrownBy(() -> row.assignIdentity(crypto.protect(7L, NationalityType.DOMESTIC, NUMBER)))
				.isInstanceOf(IllegalStateException.class);
			assertThat(row.nationality()).isNull();
		}

		@Test
		@DisplayName("정적 확인: 기존 번호 컬럼은 insertable=false·updatable=false이고 역할·비밀번호·전화·이름은 매핑하지 않는다")
		void legacyColumnReadOnly() throws Exception {
			Column legacy = IdentityMigrationMember.class.getDeclaredField("legacyNumber").getAnnotation(Column.class);
			assertThat(legacy.name()).isEqualTo("resident_registration_number");
			assertThat(legacy.insertable()).isFalse();
			assertThat(legacy.updatable()).isFalse();
			assertThat(Arrays.stream(IdentityMigrationMember.class.getDeclaredFields()).map(Field::getName))
				.containsExactlyInAnyOrder("id", "legacyNumber", "nationality", "type", "ciphertext", "hmac");
			assertThat(IdentityMigrationMember.class.getPackageName()).isEqualTo("com.wonday.migration.identity")
				.doesNotStartWith("com.wonday.farm");
		}
	}

	private static void assertIdentityEmpty(Member member) {
		for (String field : List.of("nationalityType", "identityType", "identityCiphertext", "identityHmac")) {
			assertThat(ReflectionTestUtils.getField(member, field)).isNull();
		}
	}

	private static Member member(long id) {
		Member member = new Member("worker-" + id, "$2a$hash", "근로자", MemberRole.WORKER);
		ReflectionTestUtils.setField(member, "id", id);
		ReflectionTestUtils.setField(member, "phone", "01000000000");
		return member;
	}

	static IdentityMigrationMember migrationRow(long id, String legacy) {
		IdentityMigrationMember row = BeanUtils.instantiateClass(IdentityMigrationMember.class);
		ReflectionTestUtils.setField(row, "id", id);
		ReflectionTestUtils.setField(row, "legacyNumber", legacy);
		return row;
	}
}
