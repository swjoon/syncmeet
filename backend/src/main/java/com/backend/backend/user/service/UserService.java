package com.backend.backend.user.service;

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

    private static final String GOOGLE = "GOOGLE";
    private final UserMapper userMapper;

    @Transactional(readOnly = true)
    public Optional<UserDto> findGoogleUser(String subject) {
        return userMapper.findByOauth(GOOGLE, subject);
    }

    @Transactional
    public UserDto findOrCreateGoogleUser(String subject, String name) {
        if (subject == null || subject.isBlank() || subject.length() > 255) {
            throw invalidUserInfo();
        }

        Optional<UserDto> existing = userMapper.findByOauth(GOOGLE, subject);
        if (existing.isPresent()) {
            return existing.get();
        }

        if (name == null || name.isBlank() || name.codePointCount(0, name.length()) > 100) {
            throw invalidUserInfo();
        }

        // 동시 최초 로그인도 기존 UNIQUE 제약으로 하나의 회원으로 처리한다.
        userMapper.insertIfAbsent(name, GOOGLE, subject);
        return userMapper.findByOauth(GOOGLE, subject)
                .orElseThrow(() -> new IllegalStateException("회원 생성 후 조회에 실패했습니다."));
    }

    private OAuth2AuthenticationException invalidUserInfo() {
        return new OAuth2AuthenticationException(
                new OAuth2Error("invalid_user_info"),
                "Google 사용자 정보가 없거나 저장 가능한 길이를 초과했습니다."
        );
    }
}
