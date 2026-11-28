package com.wonday.farm.change.service;

import com.wonday.farm.change.dto.WorkCancellationRequest;
import com.wonday.farm.change.dto.WorkCancellationResponse;
import com.wonday.farm.change.exception.WorkCancellationException;
import com.wonday.farm.change.validation.CancellationInput;
import com.wonday.farm.insurance.service.InsuranceTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkCancellationService {
    private final WorkChangeRecorder recorder;

    public WorkCancellationService(WorkChangeRecorder recorder) { this.recorder = recorder; }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WorkCancellationResponse cancel(long employerId, long assignmentId, WorkCancellationRequest request) {
        if (employerId <= 0 || assignmentId <= 0) throw WorkCancellationException.invalid();
        var reason = CancellationInput.reason(request);
        // 기존 변경 저장 경계를 통해서만 기록한다. 원본과 보험 조회용 불변 버전이 함께 커밋된다.
        var result = recorder.cancelBeforeStart(employerId, assignmentId, reason.reason(), reason.weather(), reason.detail());
        var change = result.change();
        var zone = InsuranceTime.zone(result.timeZone());
        return new WorkCancellationResponse(change.getAssignmentId(), change.getId(), change.getChangeType(),
            change.getReasonCode(), change.getWeatherReasonCode(), change.getReasonDetail(),
            InsuranceTime.utc(change.getEffectiveAt(), zone), InsuranceTime.utc(change.getRecordedAt(), zone),
            InsuranceTime.utc(change.getUpdatedAt(), zone), Long.toString(change.getSourceVersion()));
    }
}
