package com.wonday.farm.member.identity.migration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import com.wonday.farm.member.identity.IdentityCrypto;
import com.wonday.farm.member.identity.IdentityNumber;
import com.wonday.farm.member.identity.IdentityType;
import com.wonday.farm.member.identity.NationalityType;
import com.wonday.farm.member.identity.StoredIdentity;
import com.wonday.migration.identity.IdentityMigrationMember;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

// 웹 앱에 자동 등록하지 않는 명시적 이관 서비스다. 회원 읽기는 JPQL, 변경은 JPA 변경 감지로 처리한다.
public class MemberIdentityMigrationService {
	private final EntityManager entityManager;
	private final PlatformTransactionManager transactions;
	private final IdentityCrypto crypto;

	public MemberIdentityMigrationService(EntityManager entityManager, PlatformTransactionManager transactions,
			IdentityCrypto crypto) {
		this.entityManager = entityManager;
		this.transactions = transactions;
		this.crypto = crypto;
	}

	public Result migrate(Set<Long> confirmedIds, boolean apply) {
		if (confirmedIds == null || confirmedIds.isEmpty()
				|| confirmedIds.stream().anyMatch(id -> id == null || id <= 0)) {
			throw new MemberIdentityMigrationException("직접 확인한 내국인 회원 ID 목록이 필요합니다.");
		}
		crypto.requireConfigured();
		TransactionTemplate transaction = new TransactionTemplate(transactions);
		transaction.setReadOnly(!apply);
		transaction.setIsolationLevel(apply ? TransactionDefinition.ISOLATION_SERIALIZABLE
			: TransactionDefinition.ISOLATION_REPEATABLE_READ);
		return transaction.execute(status -> {
			Result result = validateAndMigrate(confirmedIds, apply);
			// 미리보기는 쓰기를 하지 않으며 트랜잭션도 명시적으로 롤백한다.
			if (!apply) status.setRollbackOnly();
			return result;
		});
	}

	private Result validateAndMigrate(Set<Long> confirmedIds, boolean apply) {
		var query = entityManager.createQuery("select member from IdentityMigrationMember member order by member.id",
			IdentityMigrationMember.class);
		if (apply) query.setLockMode(LockModeType.PESSIMISTIC_WRITE);
		var rows = query.getResultList();
		Map<Long, IdentityMigrationMember> byId = new HashMap<>();
		Map<String, Long> uniqueNumbers = new HashMap<>();
		for (var row : rows) {
			byId.put(row.id(), row);
			String stored = validateStored(row);
			String legacy = legacyNumber(row);
			if (stored != null) remember(uniqueNumbers, crypto.blindIndex(stored), row.id());
			if (legacy != null) {
				if (stored != null && !stored.equals(legacy)) {
					throw new MemberIdentityMigrationException("기존 번호와 저장 정보가 다릅니다. 확인할 회원 ID: " + row.id());
				}
				remember(uniqueNumbers, crypto.blindIndex(legacy), row.id());
			}
		}
		var planned = new ArrayList<StoredIdentity>();
		int alreadyMigrated = 0;
		for (long id : confirmedIds) {
			var row = byId.get(id);
			if (row == null) throw new MemberIdentityMigrationException("존재하지 않는 회원 ID: " + id);
			if (row.hasAnyStored()) {
				if (!NationalityType.DOMESTIC.name().equals(row.nationality())) {
					throw new MemberIdentityMigrationException("내국인 이관으로 변경할 수 없는 회원 ID: " + id);
				}
				alreadyMigrated++;
			} else {
				String legacy = legacyNumber(row);
				if (legacy == null) throw new MemberIdentityMigrationException("기존 식별번호가 없는 회원 ID: " + id);
				planned.add(crypto.protect(id, NationalityType.DOMESTIC, legacy));
			}
		}
		if (apply) {
			// 전체 검증이 끝난 뒤 관리 상태 엔티티의 새 필드만 바꾼다.
			// 후속 변경·flush·commit 예외는 같은 트랜잭션의 전체 롤백으로 전파한다.
			for (StoredIdentity identity : planned) byId.get(identity.memberId()).assignIdentity(identity);
			entityManager.flush();
		}
		return new Result(confirmedIds.size(), planned.size(), alreadyMigrated, apply);
	}

	private String validateStored(IdentityMigrationMember row) {
		if (!row.hasAnyStored()) return null;
		try {
			NationalityType nationality = NationalityType.valueOf(row.nationality());
			IdentityType type = IdentityType.valueOf(row.type());
			if (type != nationality.identityType() || row.hmac() == null || !row.hmac().matches("[0-9a-f]{64}")) {
				throw new IllegalArgumentException();
			}
			String number = crypto.decryptForMigration(row.id(), type, row.ciphertext());
			if (!MessageDigest.isEqual(crypto.blindIndex(number).getBytes(StandardCharsets.US_ASCII),
					row.hmac().getBytes(StandardCharsets.US_ASCII))) throw new IllegalArgumentException();
			return number;
		} catch (RuntimeException exception) {
			throw new MemberIdentityMigrationException("저장된 식별정보 또는 키 확인이 필요한 회원 ID: " + row.id());
		}
	}

	private static String legacyNumber(IdentityMigrationMember row) {
		if (row.legacy() == null || row.legacy().isBlank()) return null;
		try { return IdentityNumber.normalize(row.legacy()); }
		catch (IllegalArgumentException exception) {
			throw new MemberIdentityMigrationException("기존 번호 형식 확인이 필요한 회원 ID: " + row.id());
		}
	}

	private static void remember(Map<String, Long> indexes, String digest, long id) {
		Long previous = indexes.putIfAbsent(digest, id);
		if (previous != null && previous.longValue() != id) {
			throw new MemberIdentityMigrationException("중복 식별정보가 있습니다. 병합하지 않고 중단합니다. 회원 ID: " + previous + ", " + id);
		}
	}

	public record Result(int selected, int newlyMigrated, int alreadyMigrated, boolean applied) {}
}