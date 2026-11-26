package com.wonday.farm.insurance.dto;

public record VerifiedWorkerResponse(long workerId, String name, String phone) {
	@Override
	public String toString() { return "VerifiedWorkerResponse[redacted]"; }
}
