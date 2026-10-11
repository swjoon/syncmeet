package com.backend.backend.auth.service;

import com.backend.backend.auth.enums.OAuthProvider;
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
    private final OidcUserService delegate = createDelegate();

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
        String registrationId = userRequest.getClientRegistration().getRegistrationId(); // google, kakao
        OAuthProvider provider = OAuthProvider.fromRegistrationId(registrationId); // OAuthProvider enum: GOOGLE, KAKAO

        OidcUser oidcUser = delegate.loadUser(userRequest);
        String name = switch (provider) {
            case GOOGLE -> oidcUser.getFullName();
            // 카카오 이름 조회는 권한 신청·심사 또는 테스트 앱 설정 등 절차가 복잡해
            // 우선 프로필 닉네임을 사용한다. 닉네임도 사용자 동의가 필요하다.
            case KAKAO -> oidcUser.getClaimAsString("nickname");
        };
        try {
            userService.findOrCreateUser(provider, oidcUser.getSubject(), name);
        } catch (DataAccessException exception) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("server_error"),
                    "회원 정보를 저장하지 못했습니다.",
                    exception
            );
        }
        return oidcUser;
    }

    private static OidcUserService createDelegate() {
        OidcUserService service = new OidcUserService();
        // 카카오의 profile_nickname 범위에서도 OIDC 사용자 정보를 조회한다.
        service.setRetrieveUserInfo(request -> true);
        return service;
    }
}
