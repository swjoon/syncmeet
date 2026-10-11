package com.backend.backend.auth.enums;

import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

public enum OAuthProvider {
    GOOGLE,
    KAKAO;

    public static OAuthProvider fromRegistrationId(String registrationId) {
        if ("google".equals(registrationId)) {
            return GOOGLE;
        }
        if ("kakao".equals(registrationId)) {
            return KAKAO;
        }
        throw new OAuth2AuthenticationException(
                new OAuth2Error("unsupported_provider"),
                "지원하지 않는 로그인 제공자입니다."
        );
    }
}
