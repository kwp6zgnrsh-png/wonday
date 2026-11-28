package com.wonday.insurance.claim;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
final class AdminClaimQueryException extends RuntimeException {
	private final HttpStatus status;
	private final String code;
	AdminClaimQueryException(HttpStatus status, String code, String message) { super(message); this.status = status; this.code = code; }
	static AdminClaimQueryException invalidQuery() { return new AdminClaimQueryException(HttpStatus.BAD_REQUEST,"INVALID_CLAIM_QUERY","Invalid claim list query"); }
	static AdminClaimQueryException notFound() { return new AdminClaimQueryException(HttpStatus.NOT_FOUND,"CLAIM_NOT_FOUND","Claim not found"); }
	static AdminClaimQueryException invalidData() { return new AdminClaimQueryException(HttpStatus.INTERNAL_SERVER_ERROR,"CLAIM_DATA_INVALID","Stored claim data requires verification"); }
}
