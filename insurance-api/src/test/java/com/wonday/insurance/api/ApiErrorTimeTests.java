package com.wonday.insurance.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.endsWith;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ApiErrorTimeTests {
	@Autowired MockMvc mvc;
	@Test void frameworkJsonErrorKeepsItsStatusAndUsesTheSameGmt9Timestamp() throws Exception {
		mvc.perform(get("/error").with(request -> { request.setDispatcherType(DispatcherType.ERROR); return request; }).accept(MediaType.APPLICATION_JSON)
			.requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 404)
			.requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/api/products/99999999"))
			.andExpect(status().isNotFound()).andExpect(jsonPath("$.timestamp").value(endsWith("+09:00")))
			.andExpect(jsonPath("$.status").value(404)).andExpect(jsonPath("$.path").value("/api/products/99999999"))
			.andExpect(jsonPath("$.trace").doesNotExist());
	}
}
