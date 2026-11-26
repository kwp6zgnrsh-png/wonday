package com.wonday.insurance.platform;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties("insurance.farm")
public record FarmPlatformProperties(String baseUrl, String serviceToken, String consentVersion,
		boolean workerVerificationV2Enabled, boolean salaryAccountQueryEnabled) {
	@ConstructorBinding
	public FarmPlatformProperties {}

	public FarmPlatformProperties(String baseUrl, String serviceToken, String consentVersion) {
		this(baseUrl, serviceToken, consentVersion, false, false);
	}

	public FarmPlatformProperties(String baseUrl, String serviceToken, String consentVersion, boolean workerVerificationV2Enabled) {
		this(baseUrl, serviceToken, consentVersion, workerVerificationV2Enabled, false);
	}
	@Override
	public String toString() {
		return "FarmPlatformProperties[baseUrl=" + baseUrl + ", serviceToken=redacted, consentVersion=" + consentVersion
			+ ", workerVerificationV2Enabled=" + workerVerificationV2Enabled
			+ ", salaryAccountQueryEnabled=" + salaryAccountQueryEnabled + "]";
	}
}
