package com.backend.backend.auth;

import com.backend.backend.auth.enums.OAuthProvider;
import com.backend.backend.user.dto.UserDto;
import com.backend.backend.user.service.UserService;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthIntegrationTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    private static final FakeOidcProvider GOOGLE = new FakeOidcProvider(
            "https://accounts.google.com", "test-client-id", "openid profile",
            "name", "google-subject", "테스트 회원"
    );
    private static final FakeOidcProvider KAKAO = new FakeOidcProvider(
            null, "test-kakao-client-id", "openid profile_nickname",
            "nickname", "kakao-subject", "카카오 회원"
    );

    @Autowired
    MockMvc mvc;

    @Autowired
    UserService userService;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ClientRegistrationRepository clientRegistrations;

    @DynamicPropertySource
    static void providerEndpoints(DynamicPropertyRegistry registry) {
        registerEndpoints(registry, "google", GOOGLE);
        registerEndpoints(registry, "test-kakao", KAKAO);
        registry.add("spring.security.oauth2.client.provider.test-kakao.issuer-uri", () -> KAKAO.issuer);
    }

    private static void registerEndpoints(
            DynamicPropertyRegistry registry, String providerId, FakeOidcProvider provider
    ) {
        String prefix = "spring.security.oauth2.client.provider." + providerId + ".";
        registry.add(prefix + "authorization-uri", () -> provider.baseUrl() + "/authorize");
        registry.add(prefix + "token-uri", () -> provider.baseUrl() + "/token");
        registry.add(prefix + "user-info-uri", () -> provider.baseUrl() + "/userinfo");
        registry.add(prefix + "jwk-set-uri", () -> provider.baseUrl() + "/jwks");
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM \"TBL_USER_MAS\"");
        GOOGLE.reset("google-subject", "테스트 회원");
        KAKAO.reset("kakao-subject", "카카오 회원");
    }

    @AfterAll
    static void stopProviders() {
        GOOGLE.server.stop(0);
        KAKAO.server.stop(0);
    }

    @Test
    void unauthenticatedMeReturns401() throws Exception {
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("로그인이 필요합니다."));
    }

    @ParameterizedTest
    @EnumSource(OAuthProvider.class)
    void callbackCreatesMemberAndAuthenticatesSession(OAuthProvider provider) throws Exception {
        MockHttpSession session = login(provider);
        FakeOidcProvider remote = remote(provider);

        UserDto user = userService.findUser(provider, remote.subject.get()).orElseThrow();
        assertThat(user.getUserCode()).isPositive();
        assertThat(user.getOauthType()).isEqualTo(provider.name());
        assertThat(user.getRegDate()).isNotNull();
        assertThat(remote.userInfoRequests.get()).isEqualTo(1);

        MvcResult result = mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userCode").value(user.getUserCode()))
                .andExpect(jsonPath("$.userName").value(remote.name.get()))
                .andExpect(jsonPath("$.oauthSubject").doesNotExist())
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andReturn();

        assertThat(result.getResponse().getCharacterEncoding()).isEqualTo("UTF-8");
        assertThat(result.getResponse().getContentType()).contains("charset=UTF-8");
        assertThat(result.getResponse().getContentAsString()).contains(remote.name.get());
        if (provider == OAuthProvider.KAKAO) {
            // 카카오는 Basic 헤더 대신 POST 본문으로 클라이언트 인증을 한다.
            assertThat(remote.tokenAuthorization.get()).isNull();
            assertThat(remote.tokenRequestBody.get())
                    .contains("client_id=test-kakao-client-id", "client_secret=test-kakao-client-secret");
        }
    }

    @ParameterizedTest
    @EnumSource(OAuthProvider.class)
    void repeatLoginKeepsMemberCodeAndOriginalName(OAuthProvider provider) throws Exception {
        FakeOidcProvider remote = remote(provider);
        String originalName = remote.name.get();
        login(provider);
        Long userCode = userService.findUser(provider, remote.subject.get()).orElseThrow().getUserCode();

        remote.name.set("변경된 소셜 이름");
        MockHttpSession session = login(provider);

        mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userCode").value(userCode))
                .andExpect(jsonPath("$.userName").value(originalName));
        assertThat(memberCount()).isEqualTo(1);
    }

    @Test
    void sameSubjectAndNameFromDifferentProvidersCreateSeparateMembers() throws Exception {
        GOOGLE.subject.set("same-subject");
        KAKAO.subject.set("same-subject");
        GOOGLE.name.set("같은 이름");
        KAKAO.name.set("같은 이름");

        MockHttpSession googleSession = login(OAuthProvider.GOOGLE);
        MockHttpSession kakaoSession = login(OAuthProvider.KAKAO);
        UserDto googleUser = userService.findUser(OAuthProvider.GOOGLE, "same-subject").orElseThrow();
        UserDto kakaoUser = userService.findUser(OAuthProvider.KAKAO, "same-subject").orElseThrow();

        assertThat(googleUser.getUserCode()).isNotEqualTo(kakaoUser.getUserCode());
        assertThat(memberCount()).isEqualTo(2);
        // 요청 파라미터로 제공자를 바꾸더라도 세션의 로그인 제공자로 조회한다.
        mvc.perform(get("/api/auth/me").session(googleSession).param("provider", "kakao"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userCode").value(googleUser.getUserCode()));
        mvc.perform(get("/api/auth/me").session(kakaoSession).param("provider", "google"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userCode").value(kakaoUser.getUserCode()));
    }

    @ParameterizedTest
    @EnumSource(OAuthProvider.class)
    void invalidStateDoesNotExchangeTokenOrCreateMember(OAuthProvider provider) throws Exception {
        MvcResult start = beginLogin(provider);

        mvc.perform(get("/login/oauth2/code/" + registrationId(provider))
                        .session(sessionOf(start))
                        .param("code", "fake-code")
                        .param("state", "invalid-state"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("소셜 로그인에 실패했습니다."));

        assertThat(remote(provider).tokenRequests.get()).isZero();
        assertThat(memberCount()).isZero();
    }

    @ParameterizedTest
    @EnumSource(OAuthProvider.class)
    void callbackWithoutLoginSessionIsRejected(OAuthProvider provider) throws Exception {
        mvc.perform(get("/login/oauth2/code/" + registrationId(provider))
                        .param("code", "fake-code")
                        .param("state", "fake-state"))
                .andExpect(status().isUnauthorized());
        assertThat(remote(provider).tokenRequests.get()).isZero();
        assertThat(memberCount()).isZero();
    }

    @ParameterizedTest
    @EnumSource(OAuthProvider.class)
    void invalidNonceDoesNotCreateMember(OAuthProvider provider) throws Exception {
        MvcResult start = beginLogin(provider);
        FakeOidcProvider remote = remote(provider);
        remote.idToken.set(remote.issueIdToken("wrong-nonce", remote.signingKey, remote.issuer, remote.clientId));

        callback(provider, start).andExpect(status().isUnauthorized());
        assertThat(remote.tokenRequests.get()).isEqualTo(1);
        assertThat(memberCount()).isZero();
    }

    @ParameterizedTest
    @EnumSource(OAuthProvider.class)
    void invalidSignatureDoesNotCreateMember(OAuthProvider provider) throws Exception {
        MvcResult start = beginLogin(provider);
        FakeOidcProvider remote = remote(provider);
        remote.idToken.set(remote.issueIdToken(query(start, "nonce"), signingKey(), remote.issuer, remote.clientId));

        callback(provider, start).andExpect(status().isUnauthorized());
        assertThat(remote.tokenRequests.get()).isEqualTo(1);
        assertThat(memberCount()).isZero();
    }

    @ParameterizedTest
    @EnumSource(OAuthProvider.class)
    void invalidIssuerDoesNotCreateMember(OAuthProvider provider) throws Exception {
        MvcResult start = beginLogin(provider);
        FakeOidcProvider remote = remote(provider);
        remote.idToken.set(remote.issueIdToken(
                query(start, "nonce"), remote.signingKey, "https://invalid.example", remote.clientId));

        callback(provider, start).andExpect(status().isUnauthorized());
        assertThat(remote.tokenRequests.get()).isEqualTo(1);
        assertThat(memberCount()).isZero();
    }

    @ParameterizedTest
    @EnumSource(OAuthProvider.class)
    void invalidAudienceDoesNotCreateMember(OAuthProvider provider) throws Exception {
        MvcResult start = beginLogin(provider);
        FakeOidcProvider remote = remote(provider);
        remote.idToken.set(remote.issueIdToken(
                query(start, "nonce"), remote.signingKey, remote.issuer, "another-client"));

        callback(provider, start).andExpect(status().isUnauthorized());
        assertThat(remote.tokenRequests.get()).isEqualTo(1);
        assertThat(memberCount()).isZero();
    }

    @ParameterizedTest
    @EnumSource(OAuthProvider.class)
    void missingDisplayNamePreventsNewMemberLogin(OAuthProvider provider) throws Exception {
        remote(provider).name.set(null);
        MvcResult start = beginLogin(provider);
        setValidIdToken(provider, start);

        callback(provider, start)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("소셜 로그인에 실패했습니다."));
        assertThat(remote(provider).userInfoRequests.get()).isEqualTo(1);
        assertThat(memberCount()).isZero();
    }

    @Test
    void csrfEndpointIsPublicAndResponseIsNotCacheable() throws Exception {
        MvcResult result = mvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headerName").value("X-CSRF-TOKEN"))
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andReturn();
        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(result.getRequest().getSession(false)).isNotNull();
    }

    @ParameterizedTest
    @EnumSource(OAuthProvider.class)
    void logoutRequiresCsrfAndInvalidatesAuthenticatedSession(OAuthProvider provider) throws Exception {
        MockHttpSession session = login(provider);

        mvc.perform(post("/api/auth/logout").session(session))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk());

        MvcResult csrfResult = mvc.perform(get("/api/auth/csrf").session(session))
                .andExpect(status().isOk())
                .andReturn();
        CsrfToken csrf = (CsrfToken) csrfResult.getRequest().getAttribute(CsrfToken.class.getName());

        mvc.perform(post("/api/auth/logout")
                        .session(session)
                        .header(csrf.getHeaderName(), csrf.getToken()))
                .andExpect(status().isNoContent());

        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @EnumSource(OAuthProvider.class)
    void authenticatedUserWithoutLocalMemberReturns401(OAuthProvider provider) throws Exception {
        mvc.perform(get("/api/auth/me").with(oidcLogin()
                        .clientRegistration(clientRegistrations.findByRegistrationId(registrationId(provider)))))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @EnumSource(OAuthProvider.class)
    void concurrentFirstLoginsCreateOneMember(OAuthProvider provider) throws Exception {
        try (var executor = Executors.newFixedThreadPool(4)) {
            CountDownLatch start = new CountDownLatch(1);
            ArrayList<Future<UserDto>> results = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return userService.findOrCreateUser(provider, "concurrent-subject", "동시 로그인");
                }));
            }
            start.countDown();

            Long userCode = results.getFirst().get(15, TimeUnit.SECONDS).getUserCode();
            for (Future<UserDto> result : results) {
                assertThat(result.get(15, TimeUnit.SECONDS).getUserCode()).isEqualTo(userCode);
            }
            assertThat(memberCount()).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @EnumSource(OAuthProvider.class)
    void invalidIdentityOrNameIsNotStored(OAuthProvider provider) {
        assertThatThrownBy(() -> userService.findOrCreateUser(provider, null, "회원"))
                .isInstanceOf(OAuth2AuthenticationException.class);
        assertThatThrownBy(() -> userService.findOrCreateUser(provider, "subject", null))
                .isInstanceOf(OAuth2AuthenticationException.class);
        assertThatThrownBy(() -> userService.findOrCreateUser(provider, "subject", " "))
                .isInstanceOf(OAuth2AuthenticationException.class);
        assertThatThrownBy(() -> userService.findOrCreateUser(provider, "subject", "가".repeat(101)))
                .isInstanceOf(OAuth2AuthenticationException.class);
        assertThat(memberCount()).isZero();
    }

    @Test
    void unsupportedProviderIsRejected() {
        assertThat(OAuthProvider.fromRegistrationId("google")).isEqualTo(OAuthProvider.GOOGLE);
        assertThat(OAuthProvider.fromRegistrationId("kakao")).isEqualTo(OAuthProvider.KAKAO);
        assertThatThrownBy(() -> OAuthProvider.fromRegistrationId("unknown"))
                .isInstanceOf(OAuth2AuthenticationException.class);
        assertThatThrownBy(() -> OAuthProvider.fromRegistrationId(null))
                .isInstanceOf(OAuth2AuthenticationException.class);
    }

    private MockHttpSession login(OAuthProvider provider) throws Exception {
        MvcResult start = beginLogin(provider);
        setValidIdToken(provider, start);
        MvcResult result = callback(provider, start)
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/api/auth/me"))
                .andReturn();
        return sessionOf(result);
    }

    private void setValidIdToken(OAuthProvider provider, MvcResult start) throws JOSEException {
        FakeOidcProvider remote = remote(provider);
        remote.idToken.set(remote.issueIdToken(
                query(start, "nonce"), remote.signingKey, remote.issuer, remote.clientId));
    }

    private MvcResult beginLogin(OAuthProvider provider) throws Exception {
        return mvc.perform(get("/oauth2/authorization/" + registrationId(provider)))
                .andExpect(status().is3xxRedirection())
                .andReturn();
    }

    private ResultActions callback(OAuthProvider provider, MvcResult start) throws Exception {
        return mvc.perform(get("/login/oauth2/code/" + registrationId(provider))
                .session(sessionOf(start))
                .param("code", "fake-code")
                .param("state", query(start, "state")));
    }

    private MockHttpSession sessionOf(MvcResult result) {
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private String query(MvcResult result, String name) {
        String encoded = UriComponentsBuilder.fromUriString(result.getResponse().getRedirectedUrl())
                .build().getQueryParams().getFirst(name);
        return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
    }

    private int memberCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM \"TBL_USER_MAS\"", Integer.class);
    }

    private String registrationId(OAuthProvider provider) {
        return switch (provider) {
            case GOOGLE -> "google";
            case KAKAO -> "kakao";
        };
    }

    private FakeOidcProvider remote(OAuthProvider provider) {
        return switch (provider) {
            case GOOGLE -> GOOGLE;
            case KAKAO -> KAKAO;
        };
    }

    private static RSAKey signingKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("test-key").generate();
        } catch (JOSEException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static class FakeOidcProvider {

        private final RSAKey signingKey = signingKey();
        private final AtomicReference<String> idToken = new AtomicReference<>();
        private final AtomicReference<String> name = new AtomicReference<>();
        private final AtomicReference<String> subject = new AtomicReference<>();
        private final AtomicInteger tokenRequests = new AtomicInteger();
        private final AtomicInteger userInfoRequests = new AtomicInteger();
        private final AtomicReference<String> tokenRequestBody = new AtomicReference<>();
        private final AtomicReference<String> tokenAuthorization = new AtomicReference<>();
        private final HttpServer server;
        private final String issuer;
        private final String clientId;
        private final String scope;
        private final String nameClaim;

        private FakeOidcProvider(
                String issuer, String clientId, String scope, String nameClaim,
                String subject, String name
        ) {
            this.clientId = clientId;
            this.scope = scope;
            this.nameClaim = nameClaim;
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
            this.issuer = issuer != null ? issuer : baseUrl();
            reset(subject, name);
            server.createContext("/.well-known/openid-configuration", exchange -> sendJson(exchange, Map.of(
                    "issuer", this.issuer,
                    "authorization_endpoint", baseUrl() + "/authorize",
                    "token_endpoint", baseUrl() + "/token",
                    "userinfo_endpoint", baseUrl() + "/userinfo",
                    "jwks_uri", baseUrl() + "/jwks",
                    "response_types_supported", new String[]{"code"},
                    "subject_types_supported", new String[]{"pairwise"},
                    "id_token_signing_alg_values_supported", new String[]{"RS256"},
                    "token_endpoint_auth_methods_supported", new String[]{"client_secret_post"}
            )));
            server.createContext("/token", exchange -> {
                tokenRequests.incrementAndGet();
                tokenRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                tokenAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                sendJson(exchange, Map.of(
                        "access_token", "fake-access-token",
                        "token_type", "Bearer",
                        "expires_in", 300,
                        "scope", this.scope,
                        "id_token", idToken.get()
                ));
            });
            server.createContext("/jwks", exchange ->
                    sendJson(exchange, new JWKSet(signingKey.toPublicJWK()).toJSONObject()));
            server.createContext("/userinfo", exchange -> {
                userInfoRequests.incrementAndGet();
                Map<String, Object> claims = new LinkedHashMap<>();
                claims.put("sub", this.subject.get());
                if (this.name.get() != null) {
                    claims.put(this.nameClaim, this.name.get());
                }
                sendJson(exchange, claims);
            });
            server.start();
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private void reset(String subject, String name) {
            this.subject.set(subject);
            this.name.set(name);
            idToken.set(null);
            tokenRequestBody.set(null);
            tokenAuthorization.set(null);
            tokenRequests.set(0);
            userInfoRequests.set(0);
        }

        private String issueIdToken(String nonce, RSAKey key, String issuer, String audience) throws JOSEException {
            Instant now = Instant.now();
            // 이름·닉네임은 UserInfo에서만 제공해 실제 조회 여부를 검증한다.
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .subject(subject.get())
                    .audience(audience)
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plusSeconds(300)))
                    .claim("nonce", nonce)
                    .build();
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(), claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        }

        private static void sendJson(HttpExchange exchange, Map<String, ?> payload) throws IOException {
            byte[] body = JSONObjectUtils.toJSONString(payload).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        }
    }
}
