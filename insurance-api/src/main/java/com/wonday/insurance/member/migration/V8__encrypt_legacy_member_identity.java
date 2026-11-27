package com.wonday.insurance.member.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * 기존 DB의 V8 이력(JDBC 유형·클래스명·체크섬)을 읽기 위한 호환용 마커다.
 * 개인정보 변경은 migrateMemberIdentity 명령에서만 수행한다. 이 버전을 SQL로 교체하거나 이력을 repair하지 않는다.
 */
public final class V8__encrypt_legacy_member_identity extends BaseJavaMigration {

	@Override
	public Integer getChecksum() {
		return 1;
	}

	@Override
	public void migrate(Context context) {
		// 의도적으로 데이터에 접근하지 않는다. 미이관 데이터는 Flyway 완료 후 별도 시작 검사에서 차단한다.
		// 검사 실패를 Flyway의 실패한 V8 이력으로 남기지 않아, 수동 이관 후 repair 없이 재시작할 수 있다.
	}
}
