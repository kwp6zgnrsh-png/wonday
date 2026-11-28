package com.wonday.insurance.weather;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
class WeatherApiTests {
	private static final String ROOT="/api/admin/weather/collections", CODE="477802A001";
	private static final LocalDate DATE=LocalDate.of(2026,11,25);
	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired EntityManager entities;
	@Autowired PlatformTransactionManager transactions;
	@Autowired WeatherCollectionRepository collections;
	@Autowired WeatherCollectionAttemptRepository attempts;
	@Autowired WeatherCollectionJobRepository jobs;
	@MockitoSpyBean WeatherStationRepository stations;
	@Autowired WeatherStationService stationService;
	@Autowired WeatherCollectionService collecting;
	@Autowired GyeonggiStations scope;
	@MockitoBean RdaWeatherClient provider;
	@MockitoBean Clock clock;
	@BeforeEach void prepare() {
		tx(() -> { attempts.deleteAllInBatch(); jobs.deleteAllInBatch(); collections.deleteAllInBatch(); stations.deleteAllInBatch(); entities.createQuery("delete from Member").executeUpdate(); return null; });
		when(clock.instant()).thenReturn(Instant.parse("2026-11-27T09:00:00.123456789Z"));
		doAnswer(invocation -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			return new RdaWeatherClient.Batch(List.of("<response><sensor>X</sensor></response>"),1);
		}).when(provider).observations(anyString(),any());
	}
	@Test void savesAllRawFieldsAndReusesTheSameSourceWhileKeepingCorrections() throws Exception {
		Account admin=account(true);
		String first=mvc.perform(collect(admin)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
			.andExpect(jsonPath("$.state").value("RECEIVED")).andExpect(jsonPath("$.observationCount").value(1))
			.andExpect(jsonPath("$.collectedAt").value("2026-11-27T18:00:00.123456+09:00")).andReturn().getResponse().getContentAsString();
		long id=number(first,"$.id");
		mvc.perform(collect(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
		assertThat(collections.count()).isEqualTo(1);
		mvc.perform(get(ROOT+"/"+id).session(admin.session())).andExpect(status().isOk())
			.andExpect(jsonPath("$.pages[0]").value("<response><sensor>X</sensor></response>"));
		doReturn(new RdaWeatherClient.Batch(List.of("<response><sensor>corrected</sensor></response>"),1)).when(provider).observations(anyString(),any());
		mvc.perform(collect(admin)).andExpect(status().isOk()); assertThat(collections.count()).isEqualTo(2);
		mvc.perform(get(ROOT).session(admin.session()).param("stationCode",CODE).param("date",DATE.toString()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
	}
	@Test void emptyDataIsPreservedAsEmptyRatherThanCompleteOrZeroWeather() throws Exception {
		Account admin=account(true);
		doReturn(new RdaWeatherClient.Batch(List.of("<empty/>"),0)).when(provider).observations(anyString(),any());
		mvc.perform(collect(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("EMPTY"));
		assertThat(collections.count()).isEqualTo(1);
	}
	@Test void externalFailuresDoNotLeavePartialRecordsAndTheSameRequestCanRetry() throws Exception {
		Account admin=account(true);
		doThrow(WeatherException.unavailable()).doReturn(new RdaWeatherClient.Batch(List.of("<valid/>"),1)).when(provider).observations(anyString(),any());
		mvc.perform(collect(admin)).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("WEATHER_SERVICE_UNAVAILABLE"));
		assertThat(collections.count()).isZero();
		mvc.perform(collect(admin)).andExpect(status().isOk()); assertThat(collections.count()).isEqualTo(1);
	}
	@Test void currentRoleIsRecheckedUnderTheSaveLock() throws Exception {
		Account admin=account(true);
		doAnswer(invocation -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			jdbc.update("update member set role='WORKER' where id=?",admin.id());
			return new RdaWeatherClient.Batch(List.of("<valid/>"),1);
		}).when(provider).observations(anyString(),any());
		mvc.perform(collect(admin)).andExpect(status().isForbidden()); assertThat(collections.count()).isZero();
	}
	@Test void invalidScopeAndFutureDatesDoNotCallTheProvider() throws Exception {
		Account admin=account(true);
		for(String body:List.of("{\"stationCode\":\"not-supported\",\"date\":\"2026-11-25\"}",
			"{\"stationCode\":\"477802A001\",\"date\":\"2026-11-28\"}","{}")) {
			mvc.perform(post(ROOT).session(admin.session()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
		}
		verifyNoInteractions(provider); assertThat(collections.count()).isZero(); assertThat(jobs.count()).isZero(); assertThat(attempts.count()).isZero();
	}
	@Test void adminSessionAndCsrfAreRequiredForCollection() throws Exception {
		mvc.perform(post(ROOT).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(request())).andExpect(status().isUnauthorized());
		Account worker=account(false);
		mvc.perform(collect(worker)).andExpect(status().isForbidden());
		mvc.perform(get(ROOT).session(worker.session()).param("stationCode",CODE).param("date",DATE.toString())).andExpect(status().isForbidden());
		Account admin=account(true);
		mvc.perform(post(ROOT).session(admin.session()).contentType(MediaType.APPLICATION_JSON).content(request())).andExpect(status().isForbidden());
		jdbc.update("update member set role='WORKER' where id=?",admin.id());
		mvc.perform(collect(admin)).andExpect(status().isForbidden()); verifyNoInteractions(provider);
	}
	@Test void sourceListIsAvailableBeforeCoordinatesAndSelectionRequiresTheCompleteCatalog() throws Exception {
		Account worker=account(false);
		mvc.perform(get("/api/weather/stations").session(worker.session())).andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(24)).andExpect(jsonPath("$[0].stationCode").value("011104F031"))
			.andExpect(jsonPath("$[0].coordinatesAvailable").value(false));
		mvc.perform(get("/api/weather/stations/nearest").session(worker.session()).param("latitude","37").param("longitude","127"))
			.andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("STATION_METADATA_INCOMPLETE"));
		mvc.perform(get("/api/weather/stations/nearest").session(worker.session()).param("latitude","91").param("longitude","127"))
			.andExpect(status().isBadRequest()); verifyNoInteractions(provider);
	}
	@Test void fixedCatalogResolvesTiesByCodeWithoutCallingTheProvider() throws Exception {
		new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
			new org.springframework.core.io.ClassPathResource("db/migration/V12__seed_fixed_gyeonggi_stations.sql"))
			.execute(jdbc.getDataSource());
		jdbc.update("update weather_station set latitude=37,longitude=127 where provider='RDA'");
		Account worker=account(false);
		mvc.perform(get("/api/weather/stations/nearest").session(worker.session()).param("latitude","37").param("longitude","127"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.station.stationCode").value("011104F031"))
			.andExpect(jsonPath("$.station.metadataSyncedAt").value("2026-11-28T15:51:38.072115+09:00"))
			.andExpect(jsonPath("$.distanceMeters").value(0)).andExpect(jsonPath("$.selectionRule").value("nearest-gyeonggi-haversine-v1"));
		verifyNoInteractions(provider);
	}
	@Test void retiredSyncEndpointCannotChangeTheFixedCatalog() throws Exception {
		Account admin=account(true);
		mvc.perform(post("/api/admin/weather/stations/sync").session(admin.session()).with(csrf())).andExpect(status().isForbidden());
		verifyNoInteractions(provider);
	}
	@Test void simultaneousAdministratorsDoNotFetchTheSameTargetTwice() throws Exception {
		Account one=account(true),two=account(true);
		CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
		doAnswer(invocation -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			entered.countDown(); assertThat(release.await(15,TimeUnit.SECONDS)).isTrue();
			return new RdaWeatherClient.Batch(List.of("<same/>"),1);
		}).when(provider).observations(anyString(),any());
		ExecutorService pool=Executors.newFixedThreadPool(2);
		try {
			Future<Long> a=pool.submit(() -> collecting.collect(one.id(),CODE,DATE).id());
			assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
			Future<String> b=pool.submit(() -> {
				try { collecting.collect(two.id(),CODE,DATE); return "unexpected success"; }
				catch(WeatherException conflict) { return conflict.getCode(); }
			});
			assertThat(b.get(5,TimeUnit.SECONDS)).isEqualTo("WEATHER_COLLECTION_IN_PROGRESS");
			verify(provider,times(1)).observations(CODE,DATE);
			release.countDown(); assertThat(a.get(20,TimeUnit.SECONDS)).isPositive();
			assertThat(collections.count()).isEqualTo(1); assertThat(attempts.count()).isEqualTo(1);
		} finally { release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5,TimeUnit.SECONDS)).isTrue(); }
	}
	private MockHttpServletRequestBuilder collect(Account account) {
		return post(ROOT).session(account.session()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(request());
	}
	private static String request() { return "{\"stationCode\":\"477802A001\",\"date\":\"2026-11-25\"}"; }
	private Account account(boolean admin) throws Exception {
		String login="weather-"+UUID.randomUUID();
		String result=mvc.perform(post("/api/auth/signup").with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"loginId\":\""+login+"\",\"name\":\"Weather fixture\",\"password\":\"test-password\"}"))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		long id=number(result,"$.id"); if(admin) jdbc.update("update member set role='ADMIN' where id=?",id);
		MockHttpSession session=(MockHttpSession)mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.formField("loginId",login).formField("password","test-password")).andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
		return new Account(id,session);
	}
	private static long number(String json,String path) { return ((Number)JsonPath.read(json,path)).longValue(); }
	private <T>T tx(Supplier<T> action) { return new TransactionTemplate(transactions).execute(status -> action.get()); }
	record Account(long id,MockHttpSession session) {}
}
