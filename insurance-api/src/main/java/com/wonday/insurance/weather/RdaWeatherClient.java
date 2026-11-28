package com.wonday.insurance.weather;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

@Component
class RdaWeatherClient {
	private final RestClient http;
	private final WeatherProperties properties;
	RdaWeatherClient(@Qualifier("rdaRestClient") RestClient http, WeatherProperties properties) {
		this.http = http; this.properties = properties;
	}

	Batch observations(String stationCode, LocalDate date) {
		List<String> pages = new ArrayList<>();
		var timestamps = new HashSet<String>();
		int total = -1, received = 0;
		for (int number = 1; number <= 20; number++) {
			byte[] bytes = get("/WeatherObsrInfo/V3/GnrlWeather/getWeatherTenMinList3", properties.serviceKey(),
				Map.of("Page_No", number, "Page_Size", 144, "date", date.toString(), "obsr_Spot_Cd", stationCode));
			var page = RdaXml.page(bytes, number, 144, true);
			if (total != -1 && total != page.total()) throw WeatherException.invalid();
			total = page.total();
			for (var item : page.items()) {
				if (!RdaXml.text(item, "stn_Cd").equals(stationCode)) throw WeatherException.invalid();
				String timestamp = RdaXml.text(item, "date");
				// 원본 시각을 보존한다. 24:00 해석·강수량 차분·모델용 집계는 별도 입력 규격이 정해진 뒤 수행한다.
				if (!timestamp.matches(java.util.regex.Pattern.quote(date.toString()) + " (?:[01][0-9]|2[0-3]):[0-5][0-9](?::00)?|"
					+ java.util.regex.Pattern.quote(date.toString()) + " 24:00(?::00)?") || !timestamps.add(timestamp)) throw WeatherException.invalid();
			}
			pages.add(new String(bytes, StandardCharsets.UTF_8));
			received += page.items().size();
			if (received == total) return new Batch(List.copyOf(pages), received);
			if (received > total) throw WeatherException.invalid();
		}
		throw WeatherException.invalid();
	}

	byte[] get(String path, String key, Map<String, ?> query) {
		if (key == null || key.isBlank()) throw new WeatherException(HttpStatus.SERVICE_UNAVAILABLE,
			"WEATHER_API_NOT_CONFIGURED", "Weather API credential is not configured");
		String decoded;
		try { decoded = key.contains("%") ? URLDecoder.decode(key, StandardCharsets.UTF_8) : key; }
		catch (IllegalArgumentException exception) { throw WeatherException.unavailable(); }
		var builder = UriComponentsBuilder.fromUriString(properties.baseUrl()).path(path);
		query.forEach(builder::queryParam);
		// URI 변수를 엄격히 인코딩해 일반 키의 +와 =를 보존하고 Encoding 키의 이중 인코딩을 피한다.
		URI uri = builder.queryParam("serviceKey", "{key}").encode().buildAndExpand(decoded).toUri();
		for (int attempt = 0; attempt < 3; attempt++) {
			try {
				return http.get().uri(uri).exchange((request, response) -> {
					int status = response.getStatusCode().value();
					if (status == 429 || status >= 500) throw WeatherException.unavailable();
					if (status != 200) throw new WeatherException(HttpStatus.BAD_GATEWAY,
						"WEATHER_PROVIDER_REJECTED", "Weather provider rejected the request; check API authorization and parameters");
					byte[] bytes = response.getBody().readNBytes(2_000_001);
					if (bytes.length > 2_000_000) throw WeatherException.invalid();
					return bytes;
				});
			} catch (RestClientException exception) {
				if (attempt == 2) throw WeatherException.unavailable();
			} catch (WeatherException exception) {
				if (!exception.getCode().equals("WEATHER_SERVICE_UNAVAILABLE") || attempt == 2) throw exception;
			}
		}
		throw WeatherException.unavailable();
	}
	record Batch(List<String> pages, int itemCount) { @Override public String toString() { return "WeatherBatch[redacted]"; } }
}
