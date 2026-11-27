package com.wonday.insurance.member.migration;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MemberIdentityMigrationCommandTests {
	@Test void defaultsToPreviewAndRequiresExplicitApply() {
		var preview=MemberIdentityMigrationCommand.parseOptions(new String[]{"--confirmed-domestic-member-ids=11,12"});
		assertThat(preview.ids()).containsExactly(11L,12L);
		assertThat(preview.apply()).isFalse();
		assertThat(MemberIdentityMigrationCommand.parseOptions(new String[]{"--apply","--confirmed-domestic-member-ids=11"}).apply()).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings={"", "0", "-1", "01", "1,", "1,1", "1, 2", "not-a-number", "9223372036854775808", "1;DROP TABLE member"})
	void rejectsInvalidMemberIdsWithoutEchoingInput(String input) {
		assertThatThrownBy(() -> MemberIdentityMigrationCommand.parseOptions(new String[]{"--confirmed-domestic-member-ids="+input}))
			.isInstanceOf(MemberIdentityMigrationException.class)
			.hasMessage("확인한 내국인 회원 ID 목록과 선택적 --apply만 지정해 주세요. 중복·잘못된 옵션은 허용하지 않습니다.");
	}

	@Test void rejectsMissingUnknownAndDuplicateOptions() {
		for (String[] args:new String[][]{{}, {"--apply"}, {"--all"},
			{"--confirmed-domestic-member-ids=1","--apply","--apply"},
			{"--confirmed-domestic-member-ids=1","--confirmed-domestic-member-ids=2"}}) {
			assertThatThrownBy(() -> MemberIdentityMigrationCommand.parseOptions(args)).isInstanceOf(MemberIdentityMigrationException.class);
		}
	}
}
