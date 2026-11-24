package com.wonday.farm.job;

import static com.wonday.farm.job.JobPostingRequests.json;
import static com.wonday.farm.job.JobPostingRequests.valid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import java.util.UUID;

import com.wonday.farm.member.Member;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import com.wonday.farm.testsupport.FarmAuthClient;
import com.wonday.farm.testsupport.MySqlTestDatabase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

// 공고 INSERT가 실제로 실행된 뒤 일정 저장 단계에서 실패해도 공고까지 롤백되는지 실제 HTTP로 확인한다.
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(MySqlTestDatabase.class)
class JobPostingRollbackTests {
	private static final String PASSWORD = "rollback-test";

	@LocalServerPort
	private int port;
	@Autowired
	private MemberRepository members;
	@Autowired
	private PasswordEncoder encoder;
	@Autowired
	private JdbcTemplate jdbc;
	@MockitoSpyBean
	private JobPostingRepository postings;
	@MockitoSpyBean
	private WorkScheduleRepository schedules;

	@Test
	void failureWhileSavingSchedulesRollsBackSavedPosting() throws Exception {
		Member employer = members.save(new Member("rollback-" + UUID.randomUUID(), encoder.encode(PASSWORD),
			"롤백 테스트", MemberRole.EMPLOYER));
		doThrow(new DataIntegrityViolationException("simulated work_schedule insert failure"))
			.when(schedules).saveAll(any());
		long postingsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM job_posting", Long.class);

		try (var client = new FarmAuthClient(port)) {
			assertThat(client.login(employer.getLoginId(), PASSWORD, client.csrf()).statusCode()).isEqualTo(204);
			var response = client.post("/api/job-postings", "application/json", json(valid()), client.csrf());

			assertThat(response.statusCode()).isEqualTo(500);
		}

		// 공고 저장(IDENTITY라 즉시 INSERT)이 실행된 다음 일정 저장에서 실패했고, 결과적으로 공고 행도 남지 않아야 한다.
		verify(postings).save(any());
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM job_posting", Long.class)).isEqualTo(postingsBefore);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM job_posting WHERE employer_id = ?", Long.class,
			employer.getId())).isZero();
	}
}
