package com.wonday.farm.change.service;

import com.wonday.farm.change.dto.WorkEarlyEndRequest;
import com.wonday.farm.change.dto.WorkEarlyEndResponse;
import com.wonday.farm.change.exception.WorkEarlyEndException;
import com.wonday.farm.change.validation.EarlyEndInput;
import com.wonday.farm.insurance.service.InsuranceTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkEarlyEndService {
    private final WorkChangeRecorder recorder;
    public WorkEarlyEndService(WorkChangeRecorder recorder) { this.recorder = recorder; }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WorkEarlyEndResponse end(long employerId, long assignmentId, WorkEarlyEndRequest request) {
        if (employerId <= 0 || assignmentId <= 0) throw WorkEarlyEndException.invalid();
        var input = EarlyEndInput.request(request);
        var result = recorder.endWork(employerId, assignmentId, input.actualEndAt(),
            input.reason(), input.weather(), input.detail());
        var change = result.change();
        var zone = InsuranceTime.zone(result.timeZone());
        return new WorkEarlyEndResponse(change.getAssignmentId(), change.getId(), change.getChangeType(),
            change.getReasonCode(), change.getWeatherReasonCode(), change.getReasonDetail(),
            InsuranceTime.utc(change.getEffectiveAt(), zone), InsuranceTime.utc(change.getRecordedAt(), zone),
            InsuranceTime.utc(change.getUpdatedAt(), zone), Long.toString(change.getSourceVersion()));
    }
}
