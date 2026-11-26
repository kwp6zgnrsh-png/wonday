package com.wonday.farm.job.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;

import com.wonday.farm.auth.FarmPrincipal;
import com.wonday.farm.job.api.JobPostingApi;
import com.wonday.farm.job.dto.CreateJobPostingRequest;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

class JobPostingErrorHandlerTest {
	private final JobPostingErrorHandler handler = new JobPostingErrorHandler();

	@Test
	void scheduleErrorKeepsCodeMessageAndField() {
		var response = handler.invalidSchedule(new JobPostingException("DUPLICATE_WORK_DATE", "중복", "schedules[1].workDate"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody()).isEqualTo(
			new JobPostingErrorHandler.ErrorResponse("DUPLICATE_WORK_DATE", "중복", "schedules[1].workDate"));
	}

	@Test
	void beanValidationErrorReportsFirstFieldAsInvalidJobPosting() throws Exception {
		var binding = new BeanPropertyBindingResult(new Object(), "request");
		binding.addError(new FieldError("request", "schedules[0].workDate", "must not be null"));
		Method create = JobPostingApi.class.getMethod("create", FarmPrincipal.class, CreateJobPostingRequest.class);

		var response = handler.invalidField(new MethodArgumentNotValidException(new MethodParameter(create, 1), binding));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody().code()).isEqualTo("INVALID_JOB_POSTING");
		assertThat(response.getBody().field()).isEqualTo("schedules[0].workDate");
		assertThat(response.getBody().message()).isNotBlank();
	}

	@Test
	void unreadableJsonHasNoField() {
		var response = handler.invalidJson();

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody().code()).isEqualTo("INVALID_REQUEST_BODY");
		assertThat(response.getBody().field()).isNull();
	}

	@Test
	void accessDeniedFromServiceIsEmployerRoleRequired() {
		var response = handler.forbidden();

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(response.getBody().code()).isEqualTo("EMPLOYER_ROLE_REQUIRED");
		assertThat(response.getBody().field()).isNull();
	}

	@Test
	void exceptionTypeHandledByForbiddenIsAccessDenied() throws Exception {
		var mapping = JobPostingErrorHandler.class.getDeclaredMethod("forbidden")
			.getAnnotation(org.springframework.web.bind.annotation.ExceptionHandler.class);

		assertThat(mapping.value()).containsExactly(AccessDeniedException.class);
	}
}
