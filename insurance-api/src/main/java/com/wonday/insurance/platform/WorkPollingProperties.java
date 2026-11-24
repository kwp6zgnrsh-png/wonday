package com.wonday.insurance.platform;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("insurance.farm.polling")
record WorkPollingProperties(@Min(1) @Max(500) int pageSize, @Min(1) int maxPagesPerWorker) {}
