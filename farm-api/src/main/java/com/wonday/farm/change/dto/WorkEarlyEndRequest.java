package com.wonday.farm.change.dto;

import java.time.OffsetDateTime;
import com.wonday.farm.change.serialization.EarlyEndStringDeserializer;
import com.wonday.farm.change.serialization.EarlyEndTimeDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

// 실제 종료 시각은 오프셋을 포함해 받고, 저장 시각·고용주 ID는 서버가 결정한다.
public record WorkEarlyEndRequest(
    @JsonDeserialize(using = EarlyEndTimeDeserializer.class) OffsetDateTime actualEndAt,
    @JsonDeserialize(using = EarlyEndStringDeserializer.class) String reasonCode,
    @JsonDeserialize(using = EarlyEndStringDeserializer.class) String weatherReasonCode,
    @JsonDeserialize(using = EarlyEndStringDeserializer.class) String reasonDetail
) {}
