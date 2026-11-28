package com.wonday.insurance.claim;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import java.sql.Statement;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(properties={"insurance.farm.base-url=http://127.0.0.1:1","insurance.farm.polling.enabled=false", "insurance.weather.service-key="})
@AutoConfigureMockMvc
class ClaimWeatherStationTests {
	private static final String ROOT="/api/admin/claims", PASSWORD="test-password", CODE="477802A001";
	private static final Instant NOW=Instant.parse("2026-11-28T03:00:00.123456789Z");
	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@MockitoSpyBean ClaimRepository claims;
	@MockitoSpyBean ClaimWeatherStationRepository selections;
	@Autowired ClaimWeatherStationService selecting;
	@Autowired ClaimHistoryRepository history;
	@Autowired ObjectMapper json;
	@MockitoBean Clock clock;
	private Account admin,one,two;
	private long versionId;
	private int sequence;
	@BeforeEach void prepare() throws Exception {
		for(String table:List.of("claim_history","claim_case","platform_work_event","platform_work_cursor","insurance_contract","platform_link","product_version","insurance_product","member")) jdbc.update("delete from "+table);
		new ResourceDatabasePopulator(new ClassPathResource("db/migration/V12__seed_fixed_gyeonggi_stations.sql")).execute(jdbc.getDataSource());
		when(clock.instant()).thenReturn(NOW);
		admin=account("관리자",true); one=account("근로자 A",false); two=account("근로자 B",false); sequence=7000;
		long productId=insert("insert into insurance_product (product_code,name,description,status) values ('STATION-CLAIM','시연 보험','시연 설명','ACTIVE')");
		versionId=insert("""
			insert into product_version (product_id,version_no,sales_start_at,terms_text,premium_terms,eligible_work_groups_json,station_selection_rule,window_rule,index_rules_json,payout_rules_json)
			values (?,1,'2026-01-01 00:00:00','가입 당시 약관','{}','[]','{}','{}','{}','{}')
			""",productId);
	}

	@Test void selectsFromStoredWorkAndExposesTheSameResultInReadApisWithoutExternalCredentials() throws Exception {
		var c=claim(one,"REVIEWING"); atStation(c);
		var before=jdbc.queryForMap("select * from claim_case where id=?",c.id());
		mvc.perform(get(path(c)).session(admin.session())).andExpect(status().isOk()).andExpect(jsonPath("$.selected").value(false));
		mvc.perform(post(path(c)).session(admin.session()).with(csrf())).andExpect(status().isOk())
			.andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.claimCaseId").value(c.id()))
			.andExpect(jsonPath("$.stationCode").value(CODE)).andExpect(jsonPath("$.latitude").value(37.846217))
			.andExpect(jsonPath("$.longitude").value(127.500637)).andExpect(jsonPath("$.distanceMeters").value(0))
			.andExpect(jsonPath("$.selectionRule").value("nearest-gyeonggi-haversine-v1"))
			.andExpect(jsonPath("$.selectedAt").value("2026-11-28T03:00:00.123456Z"));
		mvc.perform(get(path(c)).session(admin.session())).andExpect(status().isOk()).andExpect(jsonPath("$.selected").value(true))
			.andExpect(jsonPath("$.selection.stationCode").value(CODE));
		mvc.perform(get(ROOT+"/"+c.id()).session(admin.session())).andExpect(status().isOk()).andExpect(jsonPath("$.weatherStation.stationCode").value(CODE));
		assertThat(jdbc.queryForMap("select * from claim_case where id=?",c.id())).isEqualTo(before);
		assertThat(history.count()).isEqualTo(1); assertThat(selections.count()).isEqualTo(1);
	}
	@Test void repeatedRequestsKeepTheFirstSelectionAndTimestampEvenAfterReviewEnds() throws Exception {
		var c=claim(one,"REVIEWING"); atStation(c);
		var original=selecting.select(admin.id(),""+c.id());
		when(clock.instant()).thenReturn(NOW.plusSeconds(600));
		jdbc.update("update claim_case set status='PAID' where id=?",c.id());
		assertThat(selecting.select(admin.id(),""+c.id())).isEqualTo(original);
		assertThat(selections.count()).isEqualTo(1);
	}
	@Test void laterPlatformSnapshotDoesNotReplaceTheClaimLocation() throws Exception {
		var c=claim(one,"REVIEWING"); atStation(c);
		ObjectNode changed=json.readValue(c.snapshot(),ObjectNode.class); changed.put("agreedLatitude",37.012578).put("agreedLongitude",126.980651);
		jdbc.update("update platform_work_event set work_snapshot=?,source_version='2' where id=?",json.writeValueAsString(changed),c.eventId());
		assertThat(selecting.select(admin.id(),""+c.id()).stationCode()).isEqualTo(CODE);
	}
	@Test void missingLegacyCoordinatesStayUnselectedAndAreNotGuessedFromAnAddress() throws Exception {
		var c=claim(one,"REVIEWING"); ObjectNode work=json.readValue(c.snapshot(),ObjectNode.class);
		work.remove("agreedLatitude"); work.remove("agreedLongitude"); replace(c,work);
		mvc.perform(post(path(c)).session(admin.session()).with(csrf())).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("WORK_COORDINATES_UNAVAILABLE"));
		mvc.perform(get(path(c)).session(admin.session())).andExpect(status().isOk()).andExpect(jsonPath("$.selected").value(false));
		assertThat(selections.count()).isZero(); assertThat(claims.count()).isEqualTo(1);
	}
	@Test void malformedOrPartialCoordinatesFailWithoutPersistingOrEchoingStoredJson() throws Exception {
		var c=claim(one,"REVIEWING");
		for(String kind:List.of("partial","text","latitude","longitude","worker","assignment")) {
			ObjectNode work=json.readValue(c.snapshot(),ObjectNode.class);
			switch(kind) {
				case "partial" -> work.remove("agreedLongitude");
				case "text" -> work.put("agreedLatitude","private-invalid-coordinate");
				case "latitude" -> work.put("agreedLatitude",91);
				case "longitude" -> work.put("agreedLongitude",181);
				case "worker" -> work.put("workerId",999999);
				default -> work.put("assignmentId",999999);
			}
			replace(c,work);
			String body=mvc.perform(post(path(c)).session(admin.session()).with(csrf())).andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value("CLAIM_DATA_INVALID")).andReturn().getResponse().getContentAsString();
			assertThat(body).doesNotContain("private-invalid-coordinate"); assertThat(selections.count()).isZero();
		}
	}
	@Test void incompleteCatalogFailsAndARequestCanSucceedAfterTheCatalogIsRestored() throws Exception {
		var c=claim(one,"REVIEWING"); atStation(c);
		jdbc.update("update weather_station set enabled=false where station_code=?",CODE);
		mvc.perform(post(path(c)).session(admin.session()).with(csrf())).andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("STATION_METADATA_INCOMPLETE"));
		assertThat(selections.count()).isZero();
		jdbc.update("update weather_station set enabled=true where station_code=?",CODE);
		assertThat(selecting.select(admin.id(),""+c.id()).stationCode()).isEqualTo(CODE);
	}
	@Test void nonReviewingUnselectedClaimsAndInconsistentOwnersAreRejected() throws Exception {
		var c=claim(one,"PAID");
		mvc.perform(post(path(c)).session(admin.session()).with(csrf())).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLAIM_NOT_REVIEWING"));
		jdbc.update("update insurance_contract set member_id=? where id=?",two.id(),c.contractId());
		mvc.perform(post(path(c)).session(admin.session()).with(csrf())).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND"));
		mvc.perform(get(path(c)).session(admin.session())).andExpect(status().isNotFound());
		assertThat(selections.count()).isZero();
	}
	@Test void adminSessionCurrentRoleAndCsrfAreRequired() throws Exception {
		var c=claim(one,"REVIEWING");
		mvc.perform(post(path(c)).with(csrf())).andExpect(status().isUnauthorized());
		mvc.perform(post(path(c)).session(one.session()).with(csrf())).andExpect(status().isForbidden());
		mvc.perform(get(path(c)).session(one.session())).andExpect(status().isForbidden());
		mvc.perform(post(path(c)).session(admin.session())).andExpect(status().isForbidden());
		jdbc.update("update member set role='WORKER' where id=?",admin.id());
		mvc.perform(post(path(c)).session(admin.session()).with(csrf())).andExpect(status().isForbidden());
		mvc.perform(get(path(c)).session(admin.session())).andExpect(status().isForbidden()); assertThat(selections.count()).isZero();
	}
	@Test void invalidAndMissingClaimIdsHaveFixedErrors() throws Exception {
		for(String id:List.of("0","-1","01","abc","9223372036854775808")) {
			mvc.perform(post(ROOT+"/"+id+"/weather-station").session(admin.session()).with(csrf())).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CLAIM_ID"));
			mvc.perform(get(ROOT+"/"+id+"/weather-station").session(admin.session())).andExpect(status().isBadRequest());
		}
		mvc.perform(post(ROOT+"/99999999/weather-station").session(admin.session()).with(csrf())).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND"));
	}
	@Test void differentAdministratorsSerializeOnTheClaimAndReturnOneSelection() throws Exception {
		var c=claim(one,"REVIEWING"); atStation(c); var secondAdmin=account("다른 관리자",true);
		CountDownLatch firstLocked=new CountDownLatch(1),secondAttempted=new CountDownLatch(1),release=new CountDownLatch(1);
		AtomicBoolean first=new AtomicBoolean(true);
		var defaultAnswer=mockingDetails(claims).getMockCreationSettings().getDefaultAnswer();
		doAnswer(invocation -> {
			if(first.compareAndSet(true,false)) {
				Object result=defaultAnswer.answer(invocation); firstLocked.countDown(); assertThat(release.await(15,TimeUnit.SECONDS)).isTrue(); return result;
			}
			secondAttempted.countDown(); return defaultAnswer.answer(invocation);
		}).when(claims).lockForStationSelection(c.id());
		ExecutorService pool=Executors.newFixedThreadPool(2);
		try {
			Future<ClaimWeatherStationView> a=pool.submit(() -> selecting.select(admin.id(),""+c.id()));
			assertThat(firstLocked.await(10,TimeUnit.SECONDS)).isTrue();
			Future<ClaimWeatherStationView> b=pool.submit(() -> selecting.select(secondAdmin.id(),""+c.id()));
			assertThat(secondAttempted.await(10,TimeUnit.SECONDS)).isTrue();
			assertThatThrownBy(() -> b.get(300,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
			verify(selections,never()).saveAndFlush(any());
			release.countDown(); assertThat(a.get(20,TimeUnit.SECONDS)).isEqualTo(b.get(20,TimeUnit.SECONDS));
			assertThat(selections.count()).isEqualTo(1);
		} finally { release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5,TimeUnit.SECONDS)).isTrue(); }
	}
	@Test void failedSaveLeavesNoSelectionAndDoesNotPreventRetry() throws Exception {
		var c=claim(one,"REVIEWING");
		var defaultAnswer=mockingDetails(selections).getMockCreationSettings().getDefaultAnswer();
		doThrow(new DataIntegrityViolationException("fixture")).doAnswer(defaultAnswer).when(selections).saveAndFlush(any());
		assertThatThrownBy(() -> selecting.select(admin.id(),""+c.id())).isInstanceOf(DataIntegrityViolationException.class);
		assertThat(selections.count()).isZero(); assertThat(claims.count()).isEqualTo(1);
		assertThat(selecting.select(admin.id(),""+c.id())).isNotNull();
	}
	private String path(Fixture claim) { return ROOT+"/"+claim.id()+"/weather-station"; }
	private void atStation(Fixture claim) throws Exception {
		ObjectNode work=json.readValue(claim.snapshot(),ObjectNode.class); work.put("agreedLatitude",37.846217).put("agreedLongitude",127.500637); replace(claim,work);
	}
	private void replace(Fixture claim,ObjectNode work) { jdbc.update("update claim_case set work_snapshot=? where id=?",json.writeValueAsString(work),claim.id()); }

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
