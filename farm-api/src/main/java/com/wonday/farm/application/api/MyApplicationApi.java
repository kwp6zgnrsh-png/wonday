package com.wonday.farm.application.api;

import com.wonday.farm.application.dto.MyApplicationResponses;
import com.wonday.farm.application.exception.MyApplicationQueryException;
import com.wonday.farm.application.service.MyApplicationQueryService;
import com.wonday.farm.auth.FarmPrincipal;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/me/applications", produces = "application/json;charset=UTF-8")
public class MyApplicationApi {
	private final MyApplicationQueryService service;
	public MyApplicationApi(MyApplicationQueryService service) { this.service = service; }

	@GetMapping
	public ResponseEntity<MyApplicationResponses.Page> list(@AuthenticationPrincipal FarmPrincipal worker,
			@RequestParam MultiValueMap<String, String> query) {
		return ok(service.list(workerId(worker), parameter(query, "status"),
			parameter(query, "limit"), parameter(query, "cursor")));
	}
	@GetMapping("/{applicationId}")
	public ResponseEntity<MyApplicationResponses.Detail> detail(@AuthenticationPrincipal FarmPrincipal worker,
			@PathVariable String applicationId) {
		return ok(service.detail(workerId(worker), positiveId(applicationId)));
	}
	@GetMapping("/{applicationId}/history")
	public ResponseEntity<MyApplicationResponses.History> history(@AuthenticationPrincipal FarmPrincipal worker,
			@PathVariable String applicationId, @RequestParam MultiValueMap<String, String> query) {
		return ok(service.history(workerId(worker), positiveId(applicationId),
			parameter(query, "limit"), parameter(query, "cursor")));
	}
	private static long workerId(FarmPrincipal principal) {
		if (principal == null) throw new AccessDeniedException("Worker session required");
		return principal.memberId();
	}
	private static long positiveId(String value) {
		try {
			if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw MyApplicationQueryException.invalidRequest();
			return Long.parseLong(value);
		} catch (NumberFormatException exception) { throw MyApplicationQueryException.invalidRequest(); }
	}
	private static String parameter(MultiValueMap<String, String> query, String name) {
		var values = query.get(name);
		if (values == null) return null;
		if (values.size() != 1) throw MyApplicationQueryException.invalidRequest();
		return values.getFirst();
	}
	private static <T> ResponseEntity<T> ok(T body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
	}
}
