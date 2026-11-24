package com.wonday.insurance.platform;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("insurance.farm")
public record FarmPlatformProperties(String baseUrl, String serviceToken, String consentVersion) {
	@Override
	public String toString() {
		return "FarmPlatformProperties[baseUrl=" + baseUrl + ", serviceToken=redacted, consentVersion=" + consentVersion + "]";
	}
}
