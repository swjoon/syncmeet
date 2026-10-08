package com.backend.backend.auth.service;

import com.backend.backend.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SyncMeetOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

    private final UserService userService;
    private final OidcUserService delegate = new OidcUserService();

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
        String registrationId = userRequest.getClientRegistration().getRegistrationId();
        if (!"google".equals(registrationId)) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("unsupported_provider"),
                    "지원하지 않는 로그인 제공자입니다."
            );
        }

        OidcUser oidcUser = delegate.loadUser(userRequest);
        try {
            userService.findOrCreateGoogleUser(oidcUser.getSubject(), oidcUser.getFullName());
        } catch (DataAccessException exception) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("server_error"),
                    "회원 정보를 저장하지 못했습니다.",
                    exception
            );
        }
        return oidcUser;
    }
}
