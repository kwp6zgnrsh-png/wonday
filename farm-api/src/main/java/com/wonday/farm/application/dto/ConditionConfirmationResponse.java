package com.wonday.farm.application.dto;

public record ConditionConfirmationResponse(long applicationId, int applicationRound,
        String status, ConditionConfirmation conditionConfirmation) {}
