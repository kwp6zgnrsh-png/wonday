package com.wonday.insurance.member.migration;

import com.wonday.insurance.member.IdentityCipher;
import com.wonday.insurance.member.IdentityType;
import com.wonday.migration.insurance.identity.IdentityMigrationMember;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Spring 빈으로 등록하지 않는다. 명시적인 명령 실행에서만 JPA 조회·변경 감지로 이관한다. */
public final class MemberIdentityMigrationService {
	private final EntityManager entities;
	private final PlatformTransactionManager transactions;
	private final IdentityCipher cipher;

	public MemberIdentityMigrationService(EntityManager entities, PlatformTransactionManager transactions, IdentityCipher cipher) {
		this.entities = entities;
		this.transactions = transactions;
		this.cipher = cipher;
	}

	public Result migrate(Set<Long> confirmedDomesticIds, boolean apply) {
		if (confirmedDomesticIds == null || confirmedDomesticIds.isEmpty()
			|| confirmedDomesticIds.stream().anyMatch(id -> id == null || id <= 0)) {
			throw new MemberIdentityMigrationException("직접 확인한 내국인 회원 ID 목록이 필요합니다.");
		}
		var tx = new TransactionTemplate(transactions);
		tx.setReadOnly(!apply);
		tx.setIsolationLevel(apply ? TransactionDefinition.ISOLATION_SERIALIZABLE : TransactionDefinition.ISOLATION_REPEATABLE_READ);
		return tx.execute(status -> {
			var query = entities.createQuery("select m from IdentityMigrationMember m where m.id in :ids order by m.id", IdentityMigrationMember.class)
				.setParameter("ids", confirmedDomesticIds);
			// 적용 시 같은 회원을 이관하는 다른 프로세스와 직렬화한다. 회원 ID 순으로 잠근다.
			if (apply) query.setLockMode(LockModeType.PESSIMISTIC_WRITE);
			var rows = query.getResultList();
			if (rows.size() != confirmedDomesticIds.size()) throw new MemberIdentityMigrationException("선택한 회원 중 존재하지 않는 ID가 있습니다.");
			Map<IdentityMigrationMember, String> planned = new LinkedHashMap<>();
			int alreadyMigrated = 0;
			for (var row : rows) {
				if (row.ciphertext() != null || row.type() != null) {
					validateEncrypted(row);
					alreadyMigrated++;
				} else {
					if (row.nationality() != null && !row.nationality().equals("DOMESTIC")) throw invalid(row.id());
					String number = normalize(row.legacy(), row.id());
					// 기존 온라인 저장과 같은 키·형식·회원별 인증 문맥을 사용한다.
					planned.put(row, cipher.encrypt(row.id(), IdentityType.RESIDENT_REGISTRATION_NUMBER, number));
				}
			}
			// 전체 선택 항목 검증 후에만 변경한다. 암호문 저장과 평문 제거는 하나의 트랜잭션이다.
			if (apply) {
				planned.forEach(IdentityMigrationMember::migrateDomestic);
				entities.flush();
			} else {
				status.setRollbackOnly();
			}
			return new Result(rows.size(), planned.size(), alreadyMigrated, apply);
		});
	}

	private void validateEncrypted(IdentityMigrationMember row) {
		if (!"DOMESTIC".equals(row.nationality()) || !IdentityType.RESIDENT_REGISTRATION_NUMBER.name().equals(row.type())
			|| row.legacy() != null) throw invalid(row.id());
		try {
			normalize(cipher.decrypt(row.id(), IdentityType.RESIDENT_REGISTRATION_NUMBER, row.ciphertext()), row.id());
		} catch (RuntimeException exception) {
			throw invalid(row.id());
		}
	}

	private static String normalize(String value, long memberId) {
		String number = value == null ? "" : value.replaceAll("(?U)[\\s-]", "");
		if (!number.matches("[0-9]{13}")) throw invalid(memberId);
		return number;
	}

	private static MemberIdentityMigrationException invalid(long id) {
		return new MemberIdentityMigrationException("식별정보·회원 구분·키를 확인해 주세요. 회원 ID: " + id);
	}

	public record Result(int selected, int newlyMigrated, int alreadyMigrated, boolean applied) {}
}
