package com.wonday.farm.member.signup.dto;

import com.wonday.farm.member.MemberRole;
import com.wonday.farm.member.identity.NationalityType;

// 가입 성공이 로그인 성공을 뜻하지 않는다. 인증·번호·계좌 정보는 포함하지 않는다.
public record SignupResponse(long id, String loginId, String name, MemberRole role, NationalityType nationalityType) {}
