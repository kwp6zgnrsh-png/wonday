package com.wonday.farm.demo.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.wonday.farm.application.entity.JobApplication;
import com.wonday.farm.job.dto.CreateJobPostingRequest;
import com.wonday.farm.job.entity.JobPosting;
import com.wonday.farm.job.entity.WorkSchedule;
import com.wonday.farm.member.Member;
import com.wonday.farm.member.MemberRole;
import com.wonday.farm.testsupport.MySqlTestDatabase;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

// 실제 Repository·Connector/J·MySQL projection 경계를 확인한다.
// 실행 시에만 Testcontainers 격리 DB를 사용하며 기본 단위 테스트에서는 제외된다.
// 테스트 작성만으로 과거 demo 500의 실제 예외 원인이 확인됐다고 보지는 않는다.
@Tag("integration")
@ActiveProfiles("farm-demo")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"spring.config.import=",
	"FARM_DEMO_EMPLOYER_PASSWORD=history-test-employer",
	"FARM_DEMO_WORKER_PASSWORD=history-test-worker"
})
@Import(MySqlTestDatabase.class)
@Transactional
class DemoJobApplicationHistoryMySqlTests {
	@Autowired private EntityManager entities;
	@Autowired private DemoJobApplicationRepository repository;
	@Autowired private DemoJobApplicationService service;

	@Test
	void actualProjectionMapsBothCurrentAndPastFlagsToNumbers() {
		var worker = member(MemberRole.WORKER);
		var posting = posting();
		var schedule = schedule(posting);
		var past = application(worker, schedule, 1);
		ReflectionTestUtils.setField(past, "status", "NOT_SELECTED");
		past.retireForReapplication();
		entities.flush();
		var current = application(worker, schedule, 2);
		entities.flush();
		entities.clear();

		var history = repository.findOwnHistory(worker.getId(), posting.getId());

		assertThat(history).extracting(DemoJobApplicationRepository.History::getApplicationId)
			.containsExactly(current.getId(), past.getId());
		assertThat(history).extracting(DemoJobApplicationRepository.History::getCurrentFlag)
			.containsExactly(1, 0);
		assertThat(history).extracting(DemoJobApplicationRepository.History::getApplicationRound)
			.containsExactly(2, 1);
		var mapped = service.history(worker.getId(), posting.getId()).applications();
		assertThat(mapped).extracting(DemoJobApplicationService.ApplicationHistory::current)
			.containsExactly(true, false);
		assertThat(mapped).extracting(DemoJobApplicationService.ApplicationHistory::status)
			.containsExactly("PENDING", "NOT_SELECTED");
	}

	@Test
	void actualQueryLimitsHistoryToOwnWorkerAndRequestedPosting() {
		var worker = member(MemberRole.WORKER);
		var otherWorker = member(MemberRole.WORKER);
		var posting = posting();
		var schedule = schedule(posting);
		var mine = application(worker, schedule, 1);
		application(otherWorker, schedule, 1);
		application(worker, schedule(posting()), 1);
		entities.flush();
		entities.clear();

		assertThat(service.history(worker.getId(), posting.getId()).applications())
			.extracting(DemoJobApplicationService.ApplicationHistory::applicationId)
			.containsExactly(mine.getId());
	}

	@Test
	void actualQueryReturnsEmptyHistoryForWorkerWithoutApplications() {
		var worker = member(MemberRole.WORKER);
		var posting = posting();
		application(member(MemberRole.WORKER), schedule(posting), 1);
		entities.flush();
		entities.clear();

		assertThat(service.history(worker.getId(), posting.getId()).applications()).isEmpty();
	}

	private Member member(MemberRole role) {
		var member = new Member("history-" + UUID.randomUUID(), "unused-test-hash", "가상 회원", role);
		entities.persist(member);
		return member;
	}

	private JobPosting posting() {
		var posting = new JobPosting(member(MemberRole.EMPLOYER), new CreateJobPostingRequest(
			"이력 조회 회귀 검증", "가상 작업", "FC03030100030103", "0003", "가상 작업지",
			new BigDecimal("37.000000"), new BigDecimal("127.000000"), "GMT+9", 3, 120000L, List.of()));
		entities.persist(posting);
		return posting;
	}

	private WorkSchedule schedule(JobPosting posting) {
		var day = LocalDate.of(2030, 1, 10);
		var schedule = new WorkSchedule(posting, new CreateJobPostingRequest.Schedule(
			day, day.atTime(9, 0), day.atTime(17, 0), null, null));
		entities.persist(schedule);
		return schedule;
	}

	private JobApplication application(Member worker, WorkSchedule schedule, int round) {
		var application = new JobApplication(worker.getId(), schedule.getId(), round, 1,
			LocalDateTime.of(2026, 11, 27, 1, 0));
		entities.persist(application);
		return application;
	}
}
