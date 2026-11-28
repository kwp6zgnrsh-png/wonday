package com.wonday.insurance.claim;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import com.wonday.insurance.member.IdentityCipher;
import com.wonday.insurance.member.IdentityType;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(properties={"insurance.farm.base-url=http://127.0.0.1:1","insurance.farm.polling.enabled=false"})
@AutoConfigureMockMvc
class AdminClaimQueryTests {
	private static final String ROOT="/api/admin/claims", PASSWORD="test-password";
	private static final Instant NOW=Instant.parse("2026-11-28T03:00:00.123456Z");
	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired ClaimRepository claims;
	@Autowired ClaimHistoryRepository history;
	@Autowired ObjectMapper json;
	@Autowired IdentityCipher cipher;
	@MockitoBean Clock clock;
	private Account admin,one,two;
	private long versionId;
	private int sequence;

	@BeforeEach void prepare() throws Exception {
		for(String table:List.of("claim_history","claim_case","platform_work_event","platform_work_cursor","insurance_contract","platform_link","product_version","insurance_product","member")) jdbc.update("delete from "+table);
		when(clock.instant()).thenReturn(NOW);
		admin=account("관리자",true); one=account("근로자 A",false); two=account("근로자 B",false); sequence=7000;
		long productId=insert("insert into insurance_product (product_code,name,description,status) values ('ADMIN-CLAIM','시연 보험','시연 설명','ACTIVE')");
		versionId=insert("""
			insert into product_version (product_id,version_no,sales_start_at,terms_text,premium_terms,eligible_work_groups_json,station_selection_rule,window_rule,index_rules_json,payout_rules_json)
			values (?,1,'2026-01-01 00:00:00','가입 당시 약관','{}','[]','{}','{}','{}','{}')
			""",productId);
	}

	@Test void listsDifferentMembersWithStatusAndMemberFiltersAndKeysetPagination() throws Exception {
		Fixture a=claim(one,"REVIEWING"), b=claim(two,"PAID"), c=claim(one,"REVIEWING");
		String first=mvc.perform(get(ROOT).session(admin.session()).param("limit","2")).andExpect(status().isOk())
			.andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.items[0].id").value(c.id())).andExpect(jsonPath("$.items[1].id").value(b.id()))
			.andExpect(jsonPath("$.hasMore").value(true)).andReturn().getResponse().getContentAsString();
		mvc.perform(get(ROOT).session(admin.session()).param("limit","2").param("beforeId",Long.toString(number(first,"$.nextBeforeId"))))
			.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(a.id())).andExpect(jsonPath("$.hasMore").value(false));
		mvc.perform(get(ROOT).session(admin.session()).param("status","REVIEWING").param("memberId",Long.toString(one.id())))
			.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.items[0].memberName").value("근로자 A"));
		mvc.perform(get(ROOT).session(admin.session()).param("status","PAID")).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(b.id()));
		mvc.perform(get(ROOT).session(admin.session()).param("status","FUTURE_STATUS")).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
	}

	@Test void detailReadsStoredContractAndFirstEventWithoutChangingWorkOffsets() throws Exception {
		var claim=claim(one,"REVIEWING");
		mvc.perform(get(ROOT+"/"+claim.id()).session(admin.session())).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
			.andExpect(jsonPath("$.claim.memberId").value(one.id())).andExpect(jsonPath("$.claim.productVersionNo").value(1))
			.andExpect(jsonPath("$.claim.openedAt").value("2026-11-28T03:00:00.123456Z"))
			.andExpect(jsonPath("$.contract.id").value(claim.contractId())).andExpect(jsonPath("$.contract.termsText").value("가입 당시 약관"))
			.andExpect(jsonPath("$.contract.status").value("ACTIVE")).andExpect(jsonPath("$.contract.coverageFrom").value("2026-11-01T00:00:00Z"))
			.andExpect(jsonPath("$.intakeSource.eventId").value(claim.eventId())).andExpect(jsonPath("$.intakeSource.weatherReasonCode").value("RAIN"))
			.andExpect(jsonPath("$.intakeSource.occurredAt").value("2026-11-24T00:00:00Z"))
			.andExpect(jsonPath("$.work.agreedWorkLocation").value("약정 당시 농장"))
			.andExpect(jsonPath("$.work.agreedStartAt").value("2026-11-25T08:00:00+09:00"))
			.andExpect(jsonPath("$.work.agreedTimeZone").value("GMT+9"));
	}

	@Test void preservesTheFirstClaimEvidenceWhenAnotherSourceVersionArrives() throws Exception {
		var claim=claim(one,"REVIEWING");
		jdbc.update("""
			insert into platform_work_event (platform_link_id,source_change_id,source_version,platform_assignment_id,event_type,reason_code,reason_detail,occurred_at,source_updated_at,received_at,intake_next_attempt_at,source_payload,work_snapshot)
			select platform_link_id,source_change_id,'2',platform_assignment_id,event_type,reason_code,'later version',occurred_at,source_updated_at,received_at,intake_next_attempt_at,
			'{"weatherReasonCode":"HEAT"}','{"agreedWorkLocation":"나중 농장"}' from platform_work_event where id=?
			""",claim.eventId());
		mvc.perform(get(ROOT+"/"+claim.id()).session(admin.session())).andExpect(status().isOk())
			.andExpect(jsonPath("$.intakeSource.sourceVersion").value("1")).andExpect(jsonPath("$.intakeSource.weatherReasonCode").value("RAIN"))
			.andExpect(jsonPath("$.work.agreedWorkLocation").value("약정 당시 농장"));
	}

	@Test void historyIsClaimScopedAndPaginatedWithNullableAutomaticActor() throws Exception {
		Fixture a=claim(one,"REVIEWING"), b=claim(two,"REVIEWING");
		for(int n=0;n<2;n++) jdbc.update("insert into claim_history (claim_case_id,action,to_status,reason,created_at) values (?,'REVIEW_NOTE','REVIEWING',?,'2026-11-28 03:00:00')",a.id(),"검토 기록 "+n);
		String first=mvc.perform(get(ROOT+"/"+a.id()+"/history").session(admin.session()).param("limit","2"))
			.andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.hasMore").value(true)).andReturn().getResponse().getContentAsString();
		mvc.perform(get(ROOT+"/"+a.id()+"/history").session(admin.session()).param("beforeId",Long.toString(number(first,"$.nextBeforeId"))))
			.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].action").value("AUTO_RECEIVED"))
			.andExpect(jsonPath("$.items[0].actorId").isEmpty()).andExpect(jsonPath("$.items[0].assessmentId").isEmpty());
		mvc.perform(get(ROOT+"/"+b.id()+"/history").session(admin.session())).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
	}

	@Test void adminAccessIsRequiredAndCurrentDatabaseRoleIsRechecked() throws Exception {
		var claim=claim(one,"REVIEWING");
		for(String path:List.of(ROOT,ROOT+"/"+claim.id(),ROOT+"/"+claim.id()+"/history")) {
			mvc.perform(get(path)).andExpect(status().isUnauthorized());
			mvc.perform(get(path).session(one.session())).andExpect(status().isForbidden());
			mvc.perform(get(path).session(two.session())).andExpect(status().isForbidden());
		}
		jdbc.update("update member set role='WORKER' where id=?",admin.id());
		for(String path:List.of(ROOT,ROOT+"/"+claim.id(),ROOT+"/"+claim.id()+"/history")) mvc.perform(get(path).session(admin.session())).andExpect(status().isForbidden());
	}

	@Test void queryDoesNotChangeClaimsEventsContractsOrHistoryAndMutationRoutesStayBlocked() throws Exception {
		var claim=claim(one,"REVIEWING");
		var claimBefore=jdbc.queryForMap("select * from claim_case where id=?",claim.id());
		var eventBefore=jdbc.queryForMap("select * from platform_work_event where id=?",claim.eventId());
		var contractBefore=jdbc.queryForMap("select * from insurance_contract where id=?",claim.contractId());
		var historyBefore=jdbc.queryForList("select * from claim_history where claim_case_id=? order by id",claim.id());
		for(String path:List.of(ROOT,ROOT+"/"+claim.id(),ROOT+"/"+claim.id()+"/history")) mvc.perform(get(path).session(admin.session())).andExpect(status().isOk());
		for(String path:List.of(ROOT,ROOT+"/"+claim.id()+"/approve",ROOT+"/"+claim.id()+"/reject")) mvc.perform(post(path).session(admin.session()).with(csrf())).andExpect(status().isForbidden());
		mvc.perform(patch(ROOT+"/"+claim.id()).session(admin.session()).with(csrf())).andExpect(status().isForbidden());
		assertThat(jdbc.queryForMap("select * from claim_case where id=?",claim.id())).isEqualTo(claimBefore);
		assertThat(jdbc.queryForMap("select * from platform_work_event where id=?",claim.eventId())).isEqualTo(eventBefore);
		assertThat(jdbc.queryForMap("select * from insurance_contract where id=?",claim.contractId())).isEqualTo(contractBefore);
		assertThat(jdbc.queryForList("select * from claim_history where claim_case_id=? order by id",claim.id())).isEqualTo(historyBefore);
	}

	@Test void legacyMissingCoordinatesAndSourceJsonRemainExplicitlyUnavailable() throws Exception {
		var claim=claim(one,"REVIEWING");
		ObjectNode work=json.readValue(claim.snapshot(),ObjectNode.class); work.remove("agreedLatitude"); work.remove("agreedLongitude");
		jdbc.update("update claim_case set work_snapshot=? where id=?",json.writeValueAsString(work),claim.id());
		jdbc.update("update platform_work_event set source_payload=null where id=?",claim.eventId());
		mvc.perform(get(ROOT+"/"+claim.id()).session(admin.session())).andExpect(status().isOk())
			.andExpect(jsonPath("$.work.agreedLatitude").isEmpty()).andExpect(jsonPath("$.work.agreedLongitude").isEmpty())
			.andExpect(jsonPath("$.intakeSource.weatherReasonCode").isEmpty());
	}

	@Test void revokedLinkAndExpiredContractDoNotHidePastClaimsOrRewriteStoredStatus() throws Exception {
		var claim=claim(one,"REVIEWING");
		jdbc.update("update platform_link set revoked_at='2026-11-27 00:00:00' where id=?",claim.linkId());
		jdbc.update("update insurance_contract set coverage_to='2026-11-26 00:00:00' where id=?",claim.contractId());
		mvc.perform(get(ROOT).session(admin.session())).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
		mvc.perform(get(ROOT+"/"+claim.id()).session(admin.session())).andExpect(status().isOk()).andExpect(jsonPath("$.contract.status").value("EXPIRED"));
		assertThat(jdbc.queryForObject("select status from insurance_contract where id=?",String.class,claim.contractId())).isEqualTo("ACTIVE");
		jdbc.update("update insurance_contract set coverage_from='2026-12-01 00:00:00',coverage_to='2027-02-01 00:00:00' where id=?",claim.contractId());
		mvc.perform(get(ROOT+"/"+claim.id()).session(admin.session())).andExpect(status().isOk()).andExpect(jsonPath("$.contract.status").value("PENDING"));
	}

	@Test void inconsistentContractAndEventOwnersAreNotCombinedIntoAValidView() throws Exception {
		var claim=claim(one,"REVIEWING");
		jdbc.update("update insurance_contract set member_id=? where id=?",two.id(),claim.contractId());
		mvc.perform(get(ROOT).session(admin.session())).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
		mvc.perform(get(ROOT+"/"+claim.id()).session(admin.session())).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND"));
		mvc.perform(get(ROOT+"/"+claim.id()+"/history").session(admin.session())).andExpect(status().isNotFound());
	}

	@Test void identityColumnsAndUnknownSnapshotOrPayloadFieldsAreNeverForwarded() throws Exception {
		var claim=claim(one,"REVIEWING"); String secret="private-identity-extra";
		String encrypted=cipher.encrypt(one.id(),IdentityType.FOREIGN_REGISTRATION_NUMBER,"9001015000000");
		jdbc.update("update member set nationality_type='FOREIGN',identity_type='FOREIGN_REGISTRATION_NUMBER',identity_number_ciphertext=?,phone='01099999999' where id=?",encrypted,one.id());
		ObjectNode work=json.readValue(claim.snapshot(),ObjectNode.class); work.put("identityNumber",secret).put("residentRegistrationNumber",secret).put("identity_number_ciphertext",encrypted);
		jdbc.update("update claim_case set work_snapshot=? where id=?",json.writeValueAsString(work),claim.id());
		jdbc.update("update platform_work_event set source_payload=? where id=?",json.writeValueAsString(Map.of("weatherReasonCode","RAIN","identityNumber",secret)),claim.eventId());
		for(String path:List.of(ROOT,ROOT+"/"+claim.id(),ROOT+"/"+claim.id()+"/history")) {
			String body=mvc.perform(get(path).session(admin.session())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
			assertThat(body).doesNotContain(secret,encrypted,"identityNumber","residentRegistrationNumber","identity_number_ciphertext","passwordHash","phone");
		}
	}

	@Test void invalidStoredWorkOrPayloadFailsSafelyWhileMetadataAndHistoryStillWork() throws Exception {
		var claim=claim(one,"REVIEWING");
		for(String invalid:List.of("[]","{\"identityNumber\":\"private-invalid-data\"}",claim.snapshot().replace("2026-11-25T08:00:00+09:00","bad-time"))) {
			jdbc.update("update claim_case set work_snapshot=? where id=?",invalid,claim.id());
			String body=mvc.perform(get(ROOT+"/"+claim.id()).session(admin.session())).andExpect(status().isInternalServerError())
				.andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.code").value("CLAIM_DATA_INVALID")).andReturn().getResponse().getContentAsString();
			assertThat(body).doesNotContain("private-invalid-data","bad-time");
		}
		mvc.perform(get(ROOT).session(admin.session())).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
		mvc.perform(get(ROOT+"/"+claim.id()+"/history").session(admin.session())).andExpect(status().isOk());
		jdbc.update("update claim_case set work_snapshot=? where id=?",claim.snapshot(),claim.id());
		jdbc.update("update platform_work_event set source_payload='{\"weatherReasonCode\":{\"privateField\":\"private-invalid-data\"}}' where id=?",claim.eventId());
		mvc.perform(get(ROOT+"/"+claim.id()).session(admin.session())).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("CLAIM_DATA_INVALID"));
	}

	@Test void missingClaimsAndInvalidIdentifiersAndPageParametersHaveDefinedErrors() throws Exception {
		var claim=claim(one,"REVIEWING");
		for(String id:List.of("0","-1","01","abc","9223372036854775808")) {
			mvc.perform(get(ROOT+"/"+id).session(admin.session())).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CLAIM_ID"));
			mvc.perform(get(ROOT+"/"+id+"/history").session(admin.session())).andExpect(status().isBadRequest());
		}
		mvc.perform(get(ROOT+"/99999999").session(admin.session())).andExpect(status().isNotFound());
		mvc.perform(get(ROOT+"/99999999/history").session(admin.session())).andExpect(status().isNotFound());
		for(String[] query:new String[][]{{"limit","0"},{"limit","101"},{"beforeId","0"},{"memberId","-1"},{"status",""},{"status","invalid status"}})
			mvc.perform(get(ROOT).session(admin.session()).param(query[0],query[1])).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CLAIM_QUERY"));
		mvc.perform(get(ROOT+"/"+claim.id()+"/history").session(admin.session()).param("limit","101")).andExpect(status().isBadRequest());
	}

	@Test void workersKeepTheirOriginalOwnedEndpointsAndAdminsUseTheirSeparateEndpoints() throws Exception {
		Fixture a=claim(one,"REVIEWING"), b=claim(two,"REVIEWING");
		mvc.perform(get("/api/claims").session(one.session())).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(a.id()));
		mvc.perform(get("/api/claims/"+b.id()).session(one.session())).andExpect(status().isNotFound());
		mvc.perform(get("/api/claims/"+a.id()).session(admin.session())).andExpect(status().isForbidden());
		mvc.perform(get(ROOT+"/"+b.id()).session(admin.session())).andExpect(status().isOk());
	}

	@Test void emptyListsAndMissingHistoryAreNotReplacedWithInventedRecords() throws Exception {
		mvc.perform(get(ROOT).session(admin.session())).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0))
			.andExpect(jsonPath("$.hasMore").value(false)).andExpect(jsonPath("$.nextBeforeId").isEmpty());
		var claim=claim(one,"REVIEWING"); jdbc.update("delete from claim_history where claim_case_id=?",claim.id());
		mvc.perform(get(ROOT+"/"+claim.id()+"/history").session(admin.session())).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
	}

	@Test void mismatchedWorkIdentityOrPartialCoordinatesAreRejectedWithoutLeakingData() throws Exception {
		var claim=claim(one,"REVIEWING");
		for(String field:List.of("assignmentId","workerId","agreedLongitude")) {
			ObjectNode work=json.readValue(claim.snapshot(),ObjectNode.class);
			if(field.equals("agreedLongitude")) work.remove(field); else work.put(field,999999);
			String snapshot=json.writeValueAsString(work); jdbc.update("update claim_case set work_snapshot=? where id=?",snapshot,claim.id());
			mvc.perform(get(ROOT+"/"+claim.id()).session(admin.session())).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("CLAIM_DATA_INVALID"));
			assertThat(json.readTree(jdbc.queryForObject("select work_snapshot from claim_case where id=?",String.class,claim.id()))).isEqualTo(work);
		}
	}

	private Fixture claim(Account owner,String status) throws Exception {
		int assignment=++sequence; long worker=owner.id()+10000L;
		var links=jdbc.queryForList("select id from platform_link where member_id=? and revoked_at is null",Long.class,owner.id());
		long linkId=links.isEmpty()?insert("insert into platform_link (member_id,platform_worker_id,consent_version,consented_at) values (?,?,'farm-platform-v1','2026-11-01 00:00:00')",owner.id(),worker):links.getFirst();
		var contracts=jdbc.queryForList("select id from insurance_contract where member_id=? and product_version_id=? order by id",Long.class,owner.id(),versionId);
		long contractId=contracts.isEmpty()?insert("insert into insurance_contract (member_id,product_version_id,accepted_at,coverage_from,coverage_to,status) values (?,?,'2026-11-01 00:00:00','2026-11-01 00:00:00','2027-02-01 00:00:00','ACTIVE')",owner.id(),versionId):contracts.getFirst();
		ObjectNode work=json.createObjectNode().put("assignmentId",assignment).put("workerId",worker)
			.put("confirmedAt","2026-11-20T09:00:00+09:00").put("agreedWorkDate","2026-11-25").put("agreedTimeZone","GMT+9")
			.put("agreedWorkLocation","약정 당시 농장").put("agreedCropCode","CABBAGE").put("agreedWorkTypeCode","HARVEST")
			.put("agreedStartAt","2026-11-25T08:00:00+09:00").put("agreedEndAt","2026-11-25T17:00:00+09:00")
			.put("agreedDailyWage",120000).put("agreedLatitude",37.123456).put("agreedLongitude",127.654321);
		work.putNull("agreedBreakStartAt").putNull("agreedBreakEndAt"); String snapshot=json.writeValueAsString(work);
		long eventId=insert("""
			insert into platform_work_event (platform_link_id,source_change_id,source_version,platform_assignment_id,event_type,reason_code,reason_detail,occurred_at,source_updated_at,received_at,intake_next_attempt_at,source_payload,work_snapshot)
			values (?,?,'1',?,'CANCELLED','WEATHER','기상 사유 취소','2026-11-24 00:00:00','2026-11-24 00:00:00','2026-11-28 03:00:00','2026-11-28 03:00:00','{"weatherReasonCode":"RAIN"}',?)
			""",linkId,assignment+100000L,assignment,snapshot);
		var saved=claims.saveAndFlush(new ClaimCase(contractId,assignment,eventId,snapshot,LocalDateTime.ofInstant(NOW,ZoneOffset.UTC)));
		if(!status.equals("REVIEWING")) jdbc.update("update claim_case set status=? where id=?",status,saved.getId());
		history.saveAndFlush(new ClaimHistory(saved.getId(),LocalDateTime.ofInstant(NOW,ZoneOffset.UTC)));
		return new Fixture(saved.getId(),contractId,eventId,linkId,snapshot);
	}
	private Account account(String name,boolean admin) throws Exception {
		String login="admin-claim-"+UUID.randomUUID();
		long id=number(mvc.perform(post("/api/auth/signup").with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content(json.writeValueAsString(Map.of("loginId",login,"name",name,"password",PASSWORD))))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(),"$.id");
		if(admin) jdbc.update("update member set role='ADMIN' where id=?",id);
		var session=(MockHttpSession)mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_FORM_URLENCODED).formField("loginId",login).formField("password",PASSWORD))
			.andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
		return new Account(id,session);
	}
	private long insert(String sql,Object... values) {
		var key=new GeneratedKeyHolder();
		jdbc.update(connection -> { var statement=connection.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS); for(int n=0;n<values.length;n++) statement.setObject(n+1,values[n]); return statement; },key);
		return key.getKey().longValue();
	}
	private static long number(String json,String path) { return ((Number)JsonPath.read(json,path)).longValue(); }
	private record Account(long id,MockHttpSession session) {}
	private record Fixture(long id,long contractId,long eventId,long linkId,String snapshot) {}
}
