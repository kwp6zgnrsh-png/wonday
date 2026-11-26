package com.wonday.insurance.platform;

// 급여 계좌 등록 상태만 제공한다. 계좌번호·예금주·리워드 대상 여부는 포함하지 않는다.
public record SalaryAccountStatus(boolean registered, String bankCode) {}
