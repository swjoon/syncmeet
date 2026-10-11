# Google·카카오 OAuth 백엔드 구현 및 테스트

## 구현 범위

- Spring Security OAuth2 Login(OIDC)과 서버 세션으로 로그인한다.
- `로그인 제공자 + sub`로 회원을 식별한다. 최초 로그인 시 Google은 이름, 카카오는 닉네임을 저장한다.
- 같은 사람이 Google과 카카오로 로그인해도 각각 별도 회원으로 등록한다. 제공자별 `sub`나 이름이 같아도 합치지 않으며, 계정 연결 기능은 제공하지 않는다.
- 재로그인 시 기존 회원 코드와 표시 이름을 유지한다.
- 이름·닉네임이 없거나 DB 길이 제한인 100자를 넘는 신규 사용자는 로그인 실패로 처리한다. 별도 닉네임 입력 화면은 구현하지 않았다.
- 회원 DB에는 이메일, 프로필 이미지, OAuth 토큰을 저장하지 않는다. Spring Security는 인증 정보 및 OAuth 클라이언트 토큰을 실행 중 세션/메모리에서 관리한다.
- 내 정보 응답에는 회원 코드와 표시 이름만 포함한다.
- 세션은 서버 재시작 시 유지되지 않는다. 운영 배포 시 HTTPS와 Secure 쿠키 설정을 추가로 검토해야 한다.

## Google 설정

1. Google Cloud Console에서 프로젝트를 만들거나 선택한다.
2. Google Auth Platform에서 앱 이름, 지원 이메일, 대상 사용자(외부), 연락처를 설정한다.
3. Clients에서 웹 애플리케이션 유형의 OAuth 클라이언트를 만든다.
4. 승인된 리디렉션 URI에 `http://localhost:8080/login/oauth2/code/google`을 등록한다.
5. 발급된 Client ID와 Client Secret을 서버 실행 환경변수에 설정한다. Secret이나 다운로드한 자격 증명 JSON을 저장소에 넣지 않는다.

현재 서버 리디렉션 URI는 `{baseUrl}/login/oauth2/code/{registrationId}`이다. 호스트 또는 포트를 변경하면 Google 등록 주소도 정확히 맞춰야 한다.

## 카카오 설정

1. Kakao Developers에서 앱을 만들거나 선택한다.
2. 카카오 로그인 사용 설정과 OpenID Connect를 활성화한다.
3. 동의항목에서 닉네임을 사용할 수 있도록 설정한다. 서버는 `openid`, `profile_nickname` 범위를 요청하며 이메일·이미지는 요청하지 않는다.
4. 로그인 리다이렉트 URI에 `http://localhost:8080/login/oauth2/code/kakao`를 등록한다.
5. REST API 키를 `KAKAO_CLIENT_ID`, 해당 키의 Client Secret을 `KAKAO_CLIENT_SECRET` 환경변수로 설정한다. 현재 서버 설정은 Client Secret을 사용하는 구성이므로 활성화 상태를 확인한다.

카카오는 `client_secret_post` 방식으로 토큰 요청 본문에 클라이언트 인증 정보를 전달한다. `issuer-uri: https://kauth.kakao.com`을 기준으로 서버 시작 시 OIDC 메타데이터를 조회하므로 카카오 인증 서버에 네트워크 접근이 필요하다.

닉네임은 카카오 OIDC 사용자 정보의 `nickname`에서 읽는다. Google의 `name`과 다르므로 제공자별로 구분해서 읽으며, 카카오의 동의 여부에 따라 닉네임이 없으면 신규 가입에 실패한다.

## 실행

Java 21과 PostgreSQL이 필요하다. 다음 환경변수를 IDE의 실행 설정 또는 터미널 환경에 넣는다.

| 환경변수 | 값 |
|---|---|
| `GOOGLE_CLIENT_ID` | 발급된 OAuth Client ID |
| `GOOGLE_CLIENT_SECRET` | 발급된 OAuth Client Secret |
| `KAKAO_CLIENT_ID` | 카카오 REST API 키 |
| `KAKAO_CLIENT_SECRET` | 해당 REST API 키의 Client Secret |
| `DB_URL` | 예: `jdbc:postgresql://localhost:5432/mydb` |
| `DB_USERNAME` | PostgreSQL 사용자 |
| `DB_PASSWORD` | PostgreSQL 비밀번호 |

Google·카카오 환경변수에는 기본값이 없으므로 네 값을 모두 지정해야 실행된다. DB 설정은 기존 로컬 기본값이 있으나 실제 사용할 DB 값으로 지정하는 것을 권장한다.

프로젝트 루트에서 실행한다.

```sh
cd backend
sh gradlew bootRun
```

## 프론트 없는 수동 확인

1. 브라우저에서 `http://localhost:8080/api/auth/me`를 열어 미인증 상태의 401을 확인한다.
2. 아래 주소 중 하나를 열어 로그인을 진행한다.

   - Google: `http://localhost:8080/oauth2/authorization/google`
   - 카카오: `http://localhost:8080/oauth2/authorization/kakao`
3. 로그인 후 `/api/auth/me`로 이동하고 다음 형태의 JSON이 반환되는지 확인한다.

```json
{"userCode": 1, "userName": "표시 이름"}
```

회원 코드는 DB가 생성하므로 예시의 1과 다를 수 있다.

4. 로그아웃은 로그인한 서버 탭의 개발자 도구 Console에서 다음 코드로 확인한다. 로그인 전 CSRF 토큰은 로그인 시 변경되므로 로그인 후 새 토큰을 조회한다.

```javascript
const csrf = await fetch('/api/auth/csrf').then(response => response.json());
const logoutResponse = await fetch('/api/auth/logout', {
  method: 'POST',
  headers: { [csrf.headerName]: csrf.token }
});
console.log(logoutResponse.status); // 204
console.log((await fetch('/api/auth/me')).status); // 401
```

5. 다시 같은 제공자의 같은 계정으로 로그인하고 같은 회원 코드와 표시 이름인지 확인한다.
6. 로그아웃 후 다른 제공자로 로그인해 별도 회원 코드가 부여되는지 확인한다.

로그아웃은 SyncMeet 세션을 종료한다. Google·카카오 계정 자체의 로그인 상태는 유지된다.

## API

| 요청 | 인증 | 동작 |
|---|---|---|
| `GET /oauth2/authorization/google` | 불필요 | Google 로그인으로 리디렉션 |
| `GET /oauth2/authorization/kakao` | 불필요 | 카카오 로그인으로 리디렉션 |
| `GET /login/oauth2/code/google` | 로그인 진행 세션 필요 | Spring Security가 콜백 처리 |
| `GET /login/oauth2/code/kakao` | 로그인 진행 세션 필요 | Spring Security가 콜백 처리 |
| `GET /api/auth/me` | 필요 | 회원 코드·표시 이름 조회 |
| `GET /api/auth/csrf` | 불필요 | CSRF 헤더 이름·토큰 조회 |
| `POST /api/auth/logout` | 세션·CSRF 토큰 사용 | 세션 종료, 204 |

미인증 보호 API 요청과 OAuth 로그인 실패는 401을 반환한다. CSRF 토큰이 없는 POST 요청은 403을 반환한다.

`/api/auth/me`는 Spring Security의 `OAuth2AuthenticationToken`에 저장된 등록 이름으로 제공자를 확인한다. 요청 파라미터로 제공자를 받지 않는다. 응답에는 기존처럼 회원 코드·이름만 포함한다.

## 자동 테스트

Docker를 실행한 뒤 `backend`에서 다음 명령을 실행한다.

```sh
sh gradlew test
```

테스트는 `test` 프로필의 가짜 클라이언트 설정을 사용하므로 실제 Google·카카오 자격 증명이 필요 없다. Testcontainers로 PostgreSQL 17을 실행하고 기존 Flyway 마이그레이션을 적용한다.

`application-test.yaml`의 카카오 등록은 테스트 전용 provider 설정인 `test-kakao`를 사용한다. 이는 서버 접속 대상 설정의 이름이며 로그인 등록 이름은 계속 `kakao`이다. 컨텍스트 로딩 테스트는 외부 OIDC 메타데이터를 조회하지 않도록 정적 가짜 주소를 사용하고, 로그인 통합 테스트는 해당 주소와 issuer를 로컬 가짜 서버로 덮어쓴다.

`AuthIntegrationTests`는 로컬 HTTP 서버 두 개로 Google·카카오의 토큰·JWK·사용자 정보 응답을 대체하며 카카오 OIDC 메타데이터 조회도 로컬에서 처리한다. 실제 Security 필터를 거치는 로그인 시작, 인증 코드 콜백, ID 토큰 서명·issuer·audience·nonce 검증, 회원 생성과 재로그인, 로그인 세션 조회, CSRF와 로그아웃을 두 제공자에 대해 검증한다.

이름은 ID 토큰 대신 UserInfo 응답에서만 제공해 실제 사용자 정보 조회와 제공자별 이름 추출을 확인한다. 카카오 토큰 요청의 Client Secret 전달 방식, 닉네임 누락 시 신규 가입 실패, 같은 `sub`와 이름을 가진 두 제공자의 회원 분리, 요청 파라미터로 제공자를 바꿔도 세션의 회원만 조회되는지, 동시 최초 로그인에서 중복 회원이 생성되지 않는지도 검증한다.

로컬 테스트의 인증 코드는 가짜 값이며 실제 Google·카카오 로그인 화면과 실제 제공자의 코드 교환은 검증하지 않는다. 실제 계정 로그인은 위 수동 절차로 별도 확인해야 한다. GitHub Actions 설정은 이번 변경에 포함하지 않았다.

## 코드 읽는 순서

1. `auth/enums/OAuthProvider.java`: 지원하는 제공자와 등록 이름 변환
2. `user/service/UserService.java`: 제공자와 식별값으로 회원 조회·가입
3. `auth/service/SyncMeetOidcUserService.java`: 제공자 확인, 사용자 정보 조회, 이름 추출, 회원 서비스 호출
4. `auth/controller/AuthController.java`: 로그인 세션에서 제공자를 확인하고 내 정보 반환
5. `config/SecurityConfig.java`, `application.yaml`: 보안 규칙과 제공자 연결 설정

공식 참고: [Google OpenID Connect](https://developers.google.com/identity/openid-connect/openid-connect), [카카오 로그인 설정](https://developers.kakao.com/docs/ko/kakaologin/prerequisite), [카카오 REST API](https://developers.kakao.com/docs/ko/kakaologin/rest-api), [Spring Security OAuth2 Login](https://docs.spring.io/spring-security/reference/servlet/oauth2/login/advanced.html).
