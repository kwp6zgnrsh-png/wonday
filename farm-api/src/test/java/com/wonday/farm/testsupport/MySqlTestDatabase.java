package com.wonday.farm.testsupport;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

// 통합 테스트용 격리 MySQL. 로컬 개발 DB(.env의 farm_db) 대신 실행마다 새 컨테이너를 쓴다.
// CI와 같은 MySQL 8.4 이미지를 사용하고, 같은 JVM의 모든 통합 테스트 컨텍스트가 컨테이너 하나를 공유한다.
@TestConfiguration(proxyBeanMethods = false)
public class MySqlTestDatabase {
	public static final String IMAGE = "mysql:8.4";

	private static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse(IMAGE))
		.withDatabaseName("farm_db");

	// 컨텍스트가 닫혀도 다른 캐시된 컨텍스트가 계속 쓰도록 컨테이너를 멈추지 않는다. JVM 종료 시 Testcontainers가 정리한다.
	@Bean(destroyMethod = "")
	@ServiceConnection
	MySQLContainer mysqlContainer() {
		return MYSQL;
	}

	// 마이그레이션 검증처럼 별도 스키마가 필요할 때 root로 접속한다.
	public static Connection rootConnection(String database) throws SQLException {
		MYSQL.start();
		String url = "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(MySQLContainer.MYSQL_PORT)
			+ "/" + database + "?allowPublicKeyRetrieval=true&useSSL=false";
		return DriverManager.getConnection(url, "root", MYSQL.getPassword());
	}

	public static String rootJdbcUrl(String database) {
		MYSQL.start();
		return "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(MySQLContainer.MYSQL_PORT)
			+ "/" + database + "?allowPublicKeyRetrieval=true&useSSL=false";
	}

	public static String rootPassword() {
		return MYSQL.getPassword();
	}
}
