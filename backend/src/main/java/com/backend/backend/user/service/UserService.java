package com.backend.backend.user.service;

import com.backend.backend.auth.enums.OAuthProvider;
import com.backend.backend.user.dto.UserDto;
import com.backend.backend.user.mapper.UserMapper;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;

    @Transactional(readOnly = true)
    public Optional<UserDto> findUser(OAuthProvider provider, String subject) {
        return userMapper.findByOauth(provider.name(), subject);
    }

    @Transactional
    public UserDto findOrCreateUser(OAuthProvider provider, String subject, String name) {
        if (subject == null || subject.isBlank() || subject.length() > 255) {
            throw invalidUserInfo();
        }

        Optional<UserDto> existing = userMapper.findByOauth(provider.name(), subject);
        // 조회된 회원이 있으면 Optional에서 회원 정보를 꺼내 반환한다.
        if (existing.isPresent()) {
            return existing.get();
        }

        if (name == null || name.isBlank() || name.codePointCount(0, name.length()) > 100) {
            throw invalidUserInfo();
        }

        // 동시 최초 로그인도 기존 UNIQUE 제약으로 하나의 회원으로 처리한다.
        userMapper.insertIfAbsent(name, provider.name(), subject);
        return userMapper.findByOauth(provider.name(), subject)
                .orElseThrow(() -> new IllegalStateException("회원 생성 후 조회에 실패했습니다."));
    }

    private OAuth2AuthenticationException invalidUserInfo() {
        return new OAuth2AuthenticationException(
                new OAuth2Error("invalid_user_info"),
                "소셜 로그인 사용자 정보가 없거나 저장 가능한 길이를 초과했습니다."
        );
    }
}
