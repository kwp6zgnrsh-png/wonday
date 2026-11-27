package com.wonday.insurance.weather;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(WeatherProperties.class)
class WeatherConfiguration {
	@Bean
	RestClient rdaRestClient() {
		HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
			.followRedirects(HttpClient.Redirect.NEVER).build();
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
		factory.setReadTimeout(Duration.ofSeconds(5));
		return RestClient.builder().requestFactory(factory).build();
	}
}
