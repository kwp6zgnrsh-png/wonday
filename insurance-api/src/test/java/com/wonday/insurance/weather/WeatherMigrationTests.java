package com.wonday.insurance.weather;

import static org.assertj.core.api.Assertions.assertThat;
import com.wonday.insurance.member.migration.V8__encrypt_legacy_member_identity;
import com.zaxxer.hikari.HikariDataSource;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@SpringBootTest
class WeatherMigrationTests {
	@Autowired DataSource dataSource;
	@Test void upgradePreservesExistingStationCoordinatesAndDoesNotEnableThemByGuessing() throws Exception {
		String schema="insurance_weather_migration_"+UUID.randomUUID().toString().replace("-","");
		HikariDataSource source=dataSource.unwrap(HikariDataSource.class);
		String url=source.getJdbcUrl().replaceFirst("/[^/?]+(?=\\?|$)","/"+schema);
		DataSource database=new DriverManagerDataSource(url,source.getUsername(),source.getPassword());
		try {
			migration(database,"9").migrate(); JdbcTemplate jdbc=new JdbcTemplate(database);
			jdbc.update("insert into weather_station (provider,station_code,name,latitude,longitude) values ('RDA','477802A001','Existing station',37.123456,127.123456)");
			var previous=jdbc.queryForMap("select id,provider,station_code,name,latitude,longitude from weather_station");
			var next=migration(database,"10"); assertThat(next.migrate().migrationsExecuted).isEqualTo(1);
			assertThat(jdbc.queryForMap("select id,provider,station_code,name,latitude,longitude from weather_station")).isEqualTo(previous);
			assertThat(jdbc.queryForObject("select count(*) from weather_station where enabled=0 and metadata_synced_at is null",Integer.class)).isEqualTo(1);
			assertThat(jdbc.queryForObject("select count(*) from weather_collection",Integer.class)).isZero();
			assertThat(next.migrate().migrationsExecuted).isZero();
		} finally {
			try(var connection=dataSource.getConnection();var statement=connection.createStatement()) {
				statement.execute("drop database if exists `"+schema+"`");
			}
		}
	}
	private Flyway migration(DataSource database,String target) {
		return Flyway.configure().dataSource(database).locations("classpath:db/migration")
			.javaMigrations(new V8__encrypt_legacy_member_identity()).target(target).load();
	}
	@Test void upgradeFromV10PreservesOriginalsAndStartsWithoutInventedHistory() throws Exception {
		String schema="insurance_weather_jobs_"+UUID.randomUUID().toString().replace("-","");
		HikariDataSource source=dataSource.unwrap(HikariDataSource.class);
		DataSource database=new DriverManagerDataSource(source.getJdbcUrl().replaceFirst("/[^/?]+(?=\\?|$)","/"+schema),source.getUsername(),source.getPassword());
		try {
			migration(database,"10").migrate(); JdbcTemplate jdbc=new JdbcTemplate(database);
			jdbc.update("insert into weather_collection (station_code,observation_date,observation_count,payload_sha256,payload_pages_json,collected_at) values ('477802A001','2026-11-25',0,?,'[\"<empty/>\"]','2026-11-27 08:00:00')","a".repeat(64));
			var before=jdbc.queryForMap("select * from weather_collection");
			var next=migration(database,"11"); assertThat(next.migrate().migrationsExecuted).isEqualTo(1);
			assertThat(jdbc.queryForMap("select * from weather_collection")).isEqualTo(before);
			assertThat(jdbc.queryForObject("select count(*) from weather_collection_job",Integer.class)).isZero();
			assertThat(jdbc.queryForObject("select count(*) from weather_collection_attempt",Integer.class)).isZero();
			assertThat(next.migrate().migrationsExecuted).isZero();
		} finally { try(var connection=dataSource.getConnection();var statement=connection.createStatement()) { statement.execute("drop database if exists `"+schema+"`"); } }
	}
}
