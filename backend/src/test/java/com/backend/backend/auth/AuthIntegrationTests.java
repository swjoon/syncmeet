package com.backend.backend.auth;

import com.backend.backend.user.dto.UserDto;
import com.backend.backend.user.service.UserService;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
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

    private static final RSAKey SIGNING_KEY = signingKey();
    private static final AtomicReference<String> ID_TOKEN = new AtomicReference<>();
    private static final AtomicReference<String> GOOGLE_NAME = new AtomicReference<>("테스트 회원");
    private static final AtomicInteger TOKEN_REQUESTS = new AtomicInteger();
    private static final HttpServer GOOGLE = googleServer();

    @Autowired
    MockMvc mvc;

    @Autowired
    UserService userService;

    @Autowired
    JdbcTemplate jdbc;

    @DynamicPropertySource
    static void googleEndpoints(DynamicPropertyRegistry registry) {
        String baseUrl = "http://127.0.0.1:" + GOOGLE.getAddress().getPort();
        String prefix = "spring.security.oauth2.client.provider.google.";
        registry.add(prefix + "authorization-uri", () -> baseUrl + "/authorize");
        registry.add(prefix + "token-uri", () -> baseUrl + "/token");
        registry.add(prefix + "user-info-uri", () -> baseUrl + "/userinfo");
        registry.add(prefix + "jwk-set-uri", () -> baseUrl + "/jwks");
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM \"TBL_USER_MAS\"");
        GOOGLE_NAME.set("테스트 회원");
        TOKEN_REQUESTS.set(0);
    }

    @AfterAll
    static void stopGoogle() {
        GOOGLE.stop(0);
    }

    @Test
    void unauthenticatedMeReturns401() throws Exception {
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("로그인이 필요합니다."));
    }

    @Test
    void googleCallbackCreatesMemberAndAuthenticatesSession() throws Exception {
        MockHttpSession session = login();

        UserDto user = userService.findGoogleUser("google-subject").orElseThrow();
        assertThat(user.getUserCode()).isPositive();
        assertThat(user.getOauthType()).isEqualTo("GOOGLE");
        assertThat(user.getRegDate()).isNotNull();

        MvcResult result = mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userCode").value(user.getUserCode()))
                .andExpect(jsonPath("$.userName").value("테스트 회원"))
                .andExpect(jsonPath("$.oauthSubject").doesNotExist())
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andReturn();

        assertThat(result.getResponse().getCharacterEncoding()).isEqualTo("UTF-8");
        assertThat(result.getResponse().getContentType()).contains("charset=UTF-8");
        assertThat(result.getResponse().getContentAsString()).contains("테스트 회원");
    }

    @Test
    void repeatGoogleLoginKeepsMemberCodeAndOriginalName() throws Exception {
        login();
        Long userCode = userService.findGoogleUser("google-subject").orElseThrow().getUserCode();

        GOOGLE_NAME.set("변경된 Google 이름");
        MockHttpSession session = login();

        mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userCode").value(userCode))
                .andExpect(jsonPath("$.userName").value("테스트 회원"));
        assertThat(memberCount()).isEqualTo(1);
    }

    @Test
    void invalidStateDoesNotExchangeTokenOrCreateMember() throws Exception {
        MvcResult start = beginLogin();

        mvc.perform(get("/login/oauth2/code/google")
                        .session(sessionOf(start))
                        .param("code", "fake-code")
                        .param("state", "invalid-state"))
                .andExpect(status().isUnauthorized());

        assertThat(TOKEN_REQUESTS.get()).isZero();
        assertThat(memberCount()).isZero();
    }

    @Test
    void callbackWithoutLoginSessionIsRejected() throws Exception {
        mvc.perform(get("/login/oauth2/code/google")
                        .param("code", "fake-code")
                        .param("state", "fake-state"))
                .andExpect(status().isUnauthorized());
        assertThat(TOKEN_REQUESTS.get()).isZero();
        assertThat(memberCount()).isZero();
    }

    @Test
    void invalidNonceDoesNotCreateMember() throws Exception {
        MvcResult start = beginLogin();
        ID_TOKEN.set(idToken("wrong-nonce", SIGNING_KEY));

        callback(start).andExpect(status().isUnauthorized());
        assertThat(TOKEN_REQUESTS.get()).isEqualTo(1);
        assertThat(memberCount()).isZero();
    }

    @Test
    void invalidSignatureDoesNotCreateMember() throws Exception {
        MvcResult start = beginLogin();
        ID_TOKEN.set(idToken(query(start, "nonce"), signingKey()));

        callback(start).andExpect(status().isUnauthorized());
        assertThat(TOKEN_REQUESTS.get()).isEqualTo(1);
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

    @Test
    void logoutRequiresCsrfAndInvalidatesAuthenticatedSession() throws Exception {
        MockHttpSession session = login();

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

    @Test
    void authenticatedGoogleUserWithoutLocalMemberReturns401() throws Exception {
        mvc.perform(get("/api/auth/me").with(oidcLogin()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void concurrentFirstLoginsCreateOneMember() throws Exception {
        try (var executor = Executors.newFixedThreadPool(4)) {
            CountDownLatch start = new CountDownLatch(1);
            ArrayList<Future<UserDto>> results = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return userService.findOrCreateGoogleUser("concurrent-subject", "동시 로그인");
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

    @Test
    void invalidGoogleIdentityOrNameIsNotStored() {
        assertThatThrownBy(() -> userService.findOrCreateGoogleUser(null, "회원"))
                .isInstanceOf(OAuth2AuthenticationException.class);
        assertThatThrownBy(() -> userService.findOrCreateGoogleUser("subject", null))
                .isInstanceOf(OAuth2AuthenticationException.class);
        assertThatThrownBy(() -> userService.findOrCreateGoogleUser("subject", " "))
                .isInstanceOf(OAuth2AuthenticationException.class);
        assertThatThrownBy(() -> userService.findOrCreateGoogleUser("subject", "가".repeat(101)))
                .isInstanceOf(OAuth2AuthenticationException.class);
        assertThat(memberCount()).isZero();
    }

    private MockHttpSession login() throws Exception {
        MvcResult start = beginLogin();
        ID_TOKEN.set(idToken(query(start, "nonce"), SIGNING_KEY));
        MvcResult result = callback(start)
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/api/auth/me"))
                .andReturn();
        return sessionOf(result);
    }

    private MvcResult beginLogin() throws Exception {
        return mvc.perform(get("/oauth2/authorization/google"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
    }

    private org.springframework.test.web.servlet.ResultActions callback(MvcResult start) throws Exception {
        return mvc.perform(get("/login/oauth2/code/google")
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

    private String idToken(String nonce, RSAKey key) throws JOSEException {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer("https://accounts.google.com")
                .subject("google-subject")
                .audience("test-client-id")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("nonce", nonce)
                .claim("name", GOOGLE_NAME.get())
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private static RSAKey signingKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("test-key").generate();
        } catch (JOSEException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static HttpServer googleServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/token", exchange -> {
                TOKEN_REQUESTS.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                byte[] body = ("{\"access_token\":\"fake-access-token\",\"token_type\":\"Bearer\","
                        + "\"expires_in\":300,\"scope\":\"openid profile\",\"id_token\":\""
                        + ID_TOKEN.get() + "\"}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            server.createContext("/jwks", exchange -> {
                byte[] body = new JWKSet(SIGNING_KEY.toPublicJWK()).toString()
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            server.createContext("/userinfo", exchange -> {
                byte[] body = ("{\"sub\":\"google-subject\",\"name\":\"" + GOOGLE_NAME.get() + "\"}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
