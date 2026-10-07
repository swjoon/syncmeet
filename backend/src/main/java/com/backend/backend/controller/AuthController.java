package com.backend.backend.controller;

import com.backend.backend.dto.CsrfResponseDto;
import com.backend.backend.dto.UserDto;
import com.backend.backend.dto.UserResponseDto;
import com.backend.backend.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    @GetMapping("/me")
    public UserResponseDto me(@AuthenticationPrincipal OidcUser principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
        }
        UserDto user = userService.findGoogleUser(principal.getSubject())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "회원 정보를 찾을 수 없습니다."));
        return new UserResponseDto(user.getUserCode(), user.getUserName());
    }

    @GetMapping("/csrf")
    public CsrfResponseDto csrf(CsrfToken csrfToken) {
        return new CsrfResponseDto(csrfToken.getHeaderName(), csrfToken.getToken());
    }
}
