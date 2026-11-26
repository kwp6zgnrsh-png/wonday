package com.wonday.farm.member.identity.migration;

import static com.wonday.farm.member.identity.testsupport.IdentityTestKeys.NUMBER;
import static com.wonday.farm.member.identity.testsupport.IdentityTestKeys.OTHER_NUMBER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.wonday.farm.member.identity.IdentityCrypto;
import com.wonday.farm.member.identity.IdentityCryptoUnavailableException;
import com.wonday.farm.member.identity.IdentityType;
import com.wonday.farm.member.identity.NationalityType;
import com.wonday.farm.member.identity.StoredIdentity;
import com.wonday.farm.member.identity.testsupport.IdentityTestKeys;
import com.wonday.migration.identity.IdentityMigrationMember;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.TypedQuery;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

// JPA 이관 서비스. EntityManager·TypedQuery·PlatformTransactionManager는 mock이고, 회원 행은 실제 IdentityMigrationMember,
// IdentityCrypto는 테스트 키의 실제 객체다. 실제 이관 명령·DB 저장·MySQL 잠금·격리 수준·커밋/롤백 결과는 검증하지 않는다.
// mock 트랜잭션 관리자는 Java 객체 값을 되돌리지 않는다. 여기서는 정의(읽기 전용·격리 수준)·rollbackOnly·rollback 호출과
// 예외 전파만 확인한다.
@DisplayName("JPA 식별정보 이관 서비스 (MemberIdentityMigrationService)")
class MemberIdentityMigrationServiceTest {
	private final EntityManager entityManager = mock(EntityManager.class);
	@SuppressWarnings("unchecked")
	private final TypedQuery<IdentityMigrationMember> query = mock(TypedQuery.class);
	private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
	private final IdentityCrypto crypto = IdentityTestKeys.crypto();
	private final MemberIdentityMigrationService service = new MemberIdentityMigrationService(entityManager, transactions, crypto);
	private final SimpleTransactionStatus status = new SimpleTransactionStatus();
	private final List<IdentityMigrationMember> rows = new ArrayList<>();

	@BeforeEach
	void setUp() {
		given(transactions.getTransaction(any())).willReturn(status);
		given(entityManager.createQuery(anyString(), eq(IdentityMigrationMember.class))).willReturn(query);
		given(query.setLockMode(any())).willReturn(query);
		given(query.getResultList()).willAnswer(invocation -> rows);
	}

	@Nested
	@DisplayName("입력·설정 사전 검사 (트랜잭션 시작 전)")
	class Preconditions {
		@Test
		@DisplayName("회원 ID 목록이 null·빈 목록·0·음수·null 원소면 거절하고 트랜잭션·조회를 시작하지 않는다")
		void invalidIds() {
			Set<Long> withNull = new LinkedHashSet<>();
			withNull.add(1L);
			withNull.add(null);
			for (Set<Long> ids : List.of(Set.<Long>of(), Set.of(0L), Set.of(-1L), Set.of(1L, -5L), withNull)) {
				assertThatThrownBy(() -> service.migrate(ids, true)).isInstanceOf(MemberIdentityMigrationException.class);
			}
			assertThatThrownBy(() -> service.migrate(null, false)).isInstanceOf(MemberIdentityMigrationException.class);
			verifyNoInteractions(transactions, entityManager);
		}

		@Test
		@DisplayName("키 설정이 불완전하면 트랜잭션 전에 IdentityCryptoUnavailableException")
		void unconfiguredCrypto() {
			var unconfigured = new MemberIdentityMigrationService(entityManager, transactions, new IdentityCrypto("", ""));

			assertThatThrownBy(() -> unconfigured.migrate(Set.of(1L), false)).isInstanceOf(IdentityCryptoUnavailableException.class);
			verifyNoInteractions(transactions, entityManager);
		}
	}

	@Nested
	@DisplayName("미리보기")
	class Preview {
		@Test
		@DisplayName("읽기 전용·REPEATABLE_READ, 잠금 없음, 엔티티 변경·flush 없음, rollbackOnly로 종료, 선택·신규·이미 이관됨 집계")
		void preview() {
			IdentityMigrationMember fresh = row(1L, "111111-1111111");
			IdentityMigrationMember migrated = migratedRow(2L, OTHER_NUMBER, OTHER_NUMBER);
			IdentityMigrationMember employer = row(3L, null);
			String migratedCiphertext = migrated.ciphertext();

			var result = service.migrate(Set.of(1L, 2L), false);

			assertThat(result).isEqualTo(new MemberIdentityMigrationService.Result(2, 1, 1, false));
			TransactionDefinition definition = definition();
			assertThat(definition.isReadOnly()).isTrue();
			assertThat(definition.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
			verify(query, never()).setLockMode(any());
			verify(entityManager, never()).flush();
			assertThat(status.isRollbackOnly()).isTrue();
			verify(transactions).commit(status); // TransactionTemplate은 rollbackOnly 상태로 commit을 요청하고 관리자가 롤백한다
			assertThat(fresh.hasAnyStored()).isFalse();
			assertThat(employer.hasAnyStored()).isFalse();
			assertThat(migrated.ciphertext()).isSameAs(migratedCiphertext);
		}
	}

	@Nested
	@DisplayName("실제 적용 분기 (mock 단위)")
	class Apply {
		@Test
		@DisplayName("SERIALIZABLE·쓰기 가능·PESSIMISTIC_WRITE, 검증 후 선택 미이관 내국인만 새 필드를 채우고 flush, 이미 이관된 행은 암호문을 다시 만들지 않음")
		void apply() {
			IdentityMigrationMember fresh = row(1L, "111111-1111111");
			IdentityMigrationMember migrated = migratedRow(2L, OTHER_NUMBER, OTHER_NUMBER);
			IdentityMigrationMember notSelected = row(3L, "333333-3333333");
			String migratedCiphertext = migrated.ciphertext();
			String migratedHmac = migrated.hmac();
			AtomicReference<String> atFlush = new AtomicReference<>();
			doAnswer(invocation -> { atFlush.set(fresh.ciphertext()); return null; }).when(entityManager).flush();

			var result = service.migrate(Set.of(1L, 2L), true);

			assertThat(result).isEqualTo(new MemberIdentityMigrationService.Result(2, 1, 1, true));
			TransactionDefinition definition = definition();
			assertThat(definition.isReadOnly()).isFalse();
			assertThat(definition.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_SERIALIZABLE);
			var order = inOrder(query, entityManager, transactions);
			order.verify(query).setLockMode(LockModeType.PESSIMISTIC_WRITE);
			order.verify(query).getResultList();
			order.verify(entityManager).flush();
			order.verify(transactions).commit(status);
			assertThat(status.isRollbackOnly()).isFalse();
			verify(transactions, never()).rollback(any());

			assertThat(fresh.nationality()).isEqualTo("DOMESTIC");
			assertThat(fresh.type()).isEqualTo("RESIDENT_REGISTRATION_NUMBER");
			assertThat(fresh.hmac()).isEqualTo(crypto.blindIndex(NUMBER));
			assertThat(crypto.decryptForMigration(1L, IdentityType.RESIDENT_REGISTRATION_NUMBER, fresh.ciphertext())).isEqualTo(NUMBER);
			assertThat(fresh.legacy()).isEqualTo("111111-1111111");
			assertThat(atFlush.get()).isEqualTo(fresh.ciphertext()); // flush는 엔티티 변경 이후
			assertThat(migrated.ciphertext()).isSameAs(migratedCiphertext);
			assertThat(migrated.hmac()).isSameAs(migratedHmac);
			assertThat(notSelected.hasAnyStored()).isFalse();
		}

		@Test
		@DisplayName("두 번째 적용은 신규 0·이미 이관됨 1이고 암호문이 그대로다 (멱등)")
		void idempotent() {
			IdentityMigrationMember fresh = row(1L, NUMBER);
			service.migrate(Set.of(1L), true);
			String first = fresh.ciphertext();

			var second = service.migrate(Set.of(1L), true);

			assertThat(second).isEqualTo(new MemberIdentityMigrationService.Result(1, 0, 1, true));
			assertThat(fresh.ciphertext()).isSameAs(first);
		}

		@Test
		@DisplayName("flush 예외는 그대로 전파되고 rollback이 요청되며 commit은 없다 (Java 객체 값 복원은 기대하지 않음)")
		void flushFailure() {
			row(1L, NUMBER);
			doThrow(new PersistenceException("constraint")).when(entityManager).flush();

			assertThatThrownBy(() -> service.migrate(Set.of(1L), true)).isInstanceOf(PersistenceException.class);
			verify(transactions).rollback(status);
			verify(transactions, never()).commit(any());
		}

		@Test
		@DisplayName("조회 예외도 전파되고 rollback이 요청된다")
		void queryFailure() {
			given(query.getResultList()).willThrow(new PersistenceException("lock timeout"));

			assertThatThrownBy(() -> service.migrate(Set.of(1L), true)).isInstanceOf(PersistenceException.class);
			verify(transactions).rollback(status);
			verify(entityManager, never()).flush();
		}
	}

	@Nested
	@DisplayName("전체 검증 실패 — 병합·변경·flush 없이 중단, rollback 요청")
	class Failures {
		@Test
		@DisplayName("정규화 후 같은 기존 번호를 가진 두 회원(선택 여부 무관)은 중복으로 중단")
		void duplicateLegacy() {
			row(1L, "111111-1111111");
			row(4L, " 1111111111111 ");

			assertAborted(() -> service.migrate(Set.of(1L), true), "1", "4");
		}

		@Test
		@DisplayName("기존 번호와 다른 회원의 저장 암호화 번호가 같으면 중복으로 중단")
		void duplicateWithStored() {
			row(1L, NUMBER);
			migratedRow(2L, null, NUMBER);

			assertAborted(() -> service.migrate(Set.of(1L), true), "1", "2");
		}

		@Test
		@DisplayName("같은 회원의 기존 번호와 복호화된 번호가 다르면 중단")
		void legacyDiffersFromStored() {
			row(1L, NUMBER);
			migratedRow(2L, "333333-3333333", OTHER_NUMBER);

			assertAborted(() -> service.migrate(Set.of(1L), true), "2");
		}

		@Test
		@DisplayName("저장값이 불완전(일부 필드만)·종류 불일치·HMAC 불일치·암호문 변조·다른 회원의 암호문이면 중단")
		void brokenStored() {
			List<java.util.function.Consumer<IdentityMigrationMember>> breakers = List.of(
				r -> { ReflectionTestUtils.setField(r, "ciphertext", null); },
				r -> { ReflectionTestUtils.setField(r, "hmac", null); },
				r -> { ReflectionTestUtils.setField(r, "type", "FOREIGN_REGISTRATION_NUMBER"); },
				r -> { ReflectionTestUtils.setField(r, "nationality", "domestic"); },
				r -> { ReflectionTestUtils.setField(r, "hmac", crypto.blindIndex(NUMBER)); },
				r -> { ReflectionTestUtils.setField(r, "hmac", crypto.blindIndex(OTHER_NUMBER).toUpperCase()); },
				r -> { ReflectionTestUtils.setField(r, "ciphertext", tamper(r.ciphertext())); },
				r -> { ReflectionTestUtils.setField(r, "ciphertext",
					crypto.protect(9L, NationalityType.DOMESTIC, OTHER_NUMBER).ciphertext()); });
			for (var breaker : breakers) {
				rows.clear();
				IdentityMigrationMember fresh = row(1L, NUMBER);
				IdentityMigrationMember broken = migratedRow(2L, OTHER_NUMBER, OTHER_NUMBER);
				breaker.accept(broken);

				assertAborted(() -> service.migrate(Set.of(1L), true), "2");
				assertThat(fresh.hasAnyStored()).isFalse();
			}
		}

		@Test
		@DisplayName("기존 번호 형식 오류(선택되지 않은 회원이라도)는 중단")
		void invalidLegacyFormat() {
			row(1L, NUMBER);
			row(5L, "12-34");

			assertAborted(() -> service.migrate(Set.of(1L), true), "5");
		}

		@Test
		@DisplayName("존재하지 않는 회원·기존 번호 없는(또는 공백) 선택 회원은 거절하고 앞서 검증된 선택 회원도 바꾸지 않는다")
		void missingOrNoNumber() {
			IdentityMigrationMember fresh = row(1L, NUMBER);
			row(6L, null);
			row(7L, "   ");

			assertAborted(() -> service.migrate(new LinkedHashSet<>(List.of(1L, 99L)), true), "99");
			assertThat(fresh.hasAnyStored()).isFalse();
			rows.clear();
			rows.add(fresh);
			row(6L, null);
			row(7L, "   ");
			assertAborted(() -> service.migrate(new LinkedHashSet<>(List.of(1L, 6L)), true), "6");
			assertAborted(() -> service.migrate(new LinkedHashSet<>(List.of(1L, 7L)), true), "7");
			assertThat(fresh.hasAnyStored()).isFalse();
		}

		@Test
		@DisplayName("기존 외국인 식별정보 회원을 선택하면 내국인으로 바꾸지 않고 중단")
		void foreignNotConverted() {
			IdentityMigrationMember foreign = row(8L, null);
			StoredIdentity stored = crypto.protect(8L, NationalityType.FOREIGN, OTHER_NUMBER);
			foreign.assignIdentity(stored);

			assertAborted(() -> service.migrate(Set.of(8L), true), "8");
			assertThat(foreign.nationality()).isEqualTo("FOREIGN");
			assertThat(foreign.ciphertext()).isEqualTo(stored.ciphertext());
		}

		@Test
		@DisplayName("미리보기에서도 같은 검증 실패로 중단한다")
		void previewAlsoValidates() {
			row(1L, NUMBER);
			row(4L, NUMBER);

			assertThatThrownBy(() -> service.migrate(Set.of(1L), false)).isInstanceOf(MemberIdentityMigrationException.class);
			verify(entityManager, never()).flush();
		}

		private void assertAborted(ThrowingCallable call, String... ids) {
			Throwable error = org.assertj.core.api.Assertions.catchThrowable(call);
			assertThat(error).isExactlyInstanceOf(MemberIdentityMigrationException.class).hasNoCause();
			assertThat(error.getMessage()).contains(ids)
				.doesNotContain(NUMBER, OTHER_NUMBER, "111111-", "333333", crypto.blindIndex(NUMBER), crypto.blindIndex(OTHER_NUMBER));
			verify(entityManager, never()).flush();
			verify(transactions, org.mockito.Mockito.atLeastOnce()).rollback(status);
			verify(transactions, never()).commit(any());
		}
	}

	private TransactionDefinition definition() {
		ArgumentCaptor<TransactionDefinition> captor = ArgumentCaptor.forClass(TransactionDefinition.class);
		verify(transactions, org.mockito.Mockito.atLeastOnce()).getTransaction(captor.capture());
		return captor.getValue();
	}

	private IdentityMigrationMember row(long id, String legacy) {
		IdentityMigrationMember row = BeanUtils.instantiateClass(IdentityMigrationMember.class);
		ReflectionTestUtils.setField(row, "id", id);
		ReflectionTestUtils.setField(row, "legacyNumber", legacy);
		rows.add(row);
		return row;
	}

	private IdentityMigrationMember migratedRow(long id, String legacy, String storedNumber) {
		IdentityMigrationMember row = row(id, legacy);
		row.assignIdentity(crypto.protect(id, NationalityType.DOMESTIC, storedNumber));
		return row;
	}

	private static String tamper(String ciphertext) {
		char last = ciphertext.charAt(ciphertext.length() - 10);
		return ciphertext.substring(0, ciphertext.length() - 10) + (last == 'A' ? 'B' : 'A') + ciphertext.substring(ciphertext.length() - 9);
	}
}
