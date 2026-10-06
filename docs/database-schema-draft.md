# SyncMeet DB 구조 초안

기획서 v0.1의 T1 범위를 기준으로 한다. 실제 SQL 및 DB에는 아직 적용하지 않았다.

## 공통 규칙

- 테이블명: `TBL_{대구분}_{소구분}`
- 컬럼명: `{대구분 첫글자}{소구분 첫글자}_{대구분}_{데이터}`, 소문자 사용
- 기본 컬럼명에 언더바가 3개 이상이면 대구분 이름 생략
- 일시 컬럼은 `_at` 대신 `_time` 사용
- 모든 테이블에 `code` PK 존재: BIGINT, 1부터 시작해 1씩 자동 증가
- 등록일시: `{첫글자 조합}_reg_date`, `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`
- 아래 표의 ‘선택’ 컬럼만 NULL 허용

## 1. TBL_USER_MAS — 회원

회원 한 명당 OAuth 로그인 계정 하나를 저장한다. 별도 OAuth 테이블은 두지 않는다.

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `um_user_code` | BIGINT | PK |
| `um_user_name` | VARCHAR(100) | 표시 이름 |
| `um_oauth_type` | VARCHAR(20) | 로그인 제공자, T1은 GOOGLE |
| `um_oauth_subject` | VARCHAR(255) | 제공자의 사용자 고유 식별값 |
| `um_reg_date` | TIMESTAMPTZ | 등록일시 |

- UNIQUE: `um_oauth_type + um_oauth_subject`
- 이메일·프로필 이미지·OAuth 토큰은 현재 초안에서 저장하지 않는다.
- 로그인 시 받은 이름 또는 사용자가 정한 표시 이름을 저장한다.
- 여러 로그인 계정을 한 회원에 연결하는 기능은 현재 범위에서 제외한다.

## 2. TBL_MEETING_MAS — 약속

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `mm_meeting_code` | BIGINT | PK |
| `mm_host_code` | BIGINT | 호스트 회원 FK |
| `mm_meeting_title` | VARCHAR(200) | 약속 제목 |
| `mm_start_date` | DATE | 후보 기간 시작일 |
| `mm_end_date` | DATE | 후보 기간 종료일 |
| `mm_duration_minutes` | INTEGER | 예상 소요시간, 분 |
| `mm_meeting_budget` | NUMERIC(12, 0) | 예산, 선택 |
| `mm_meeting_memo` | TEXT | 메모, 선택 |
| `mm_meeting_status` | VARCHAR(20) | 상태, 기본값 OPEN |
| `mm_confirmed_start_time` | TIMESTAMPTZ | 확정 시작일시, 선택 |
| `mm_confirmed_end_time` | TIMESTAMPTZ | 확정 종료일시, 선택 |
| `mm_place_name` | VARCHAR(200) | 직접 입력한 장소명, 선택 |
| `mm_place_address` | TEXT | 장소 주소, 선택 |
| `mm_reg_date` | TIMESTAMPTZ | 등록일시 |

- FK: `mm_host_code` → `TBL_USER_MAS.um_user_code`
- 제안 상태: `OPEN`, `CONFIRMED`, `CANCELLED`
- 제안 제약: 후보 종료일 ≥ 시작일, 소요시간 > 0, 예산 ≥ 0
- CONFIRMED 상태에는 확정 시작·종료일시와 장소명 필수
- 확정 종료일시는 확정 시작일시보다 이후여야 한다.

## 3. TBL_MEETING_MEMBER — 약속 참여자

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `mm_meeting_code` | BIGINT | 참여자 레코드 PK |
| `mm_ref_code` | BIGINT | 약속 FK |
| `mm_user_code` | BIGINT | 회원 FK |
| `mm_availability_submitted_time` | TIMESTAMPTZ | 가능 시간 제출일시, 선택 |
| `mm_reg_date` | TIMESTAMPTZ | 등록일시 |

- FK: `mm_ref_code` → `TBL_MEETING_MAS.mm_meeting_code`
- FK: `mm_user_code` → `TBL_USER_MAS.um_user_code`
- UNIQUE: `mm_ref_code + mm_user_code`
- 호스트도 참여자로 등록하는 구조를 제안한다.
- 제출일시로 미입력과 ‘가능한 시간 없음으로 제출’을 구분한다.
- `mm_meeting_code`는 이 테이블의 참여자 PK이며, 약속 자체의 코드는 `mm_ref_code`이다.

## 4. TBL_MEETING_INVITE — 초대 링크

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `mi_meeting_code` | BIGINT | 초대 레코드 PK |
| `mi_ref_code` | BIGINT | 약속 FK |
| `mi_token_hash` | VARCHAR(64) | 초대 토큰 SHA-256 해시 |
| `mi_expires_time` | TIMESTAMPTZ | 만료일시 |
| `mi_revoked_time` | TIMESTAMPTZ | 초대 취소일시, 선택 |
| `mi_reg_date` | TIMESTAMPTZ | 등록일시 |

- FK: `mi_ref_code` → `TBL_MEETING_MAS.mm_meeting_code`
- UNIQUE: `mi_token_hash`
- 원본 초대 토큰은 저장하지 않는 구조를 제안한다.
- 초대 링크의 만료 기간은 미정이다.

## 5. TBL_AVAILABILITY_SLOT — 가능한 시간 구간

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `as_availability_code` | BIGINT | PK |
| `as_member_code` | BIGINT | 참여자 FK |
| `as_start_time` | TIMESTAMPTZ | 가능 구간 시작일시 |
| `as_end_time` | TIMESTAMPTZ | 가능 구간 종료일시 |
| `as_availability_preference` | SMALLINT | 선호도, 기본값 0 |
| `as_reg_date` | TIMESTAMPTZ | 등록일시 |

- FK: `as_member_code` → `TBL_MEETING_MEMBER.mm_meeting_code`
- 한 참여자가 여러 시간 구간을 등록할 수 있다.
- 제안 제약: 종료일시 > 시작일시
- 제안 선호도: `0 = 가능`, `1 = 선호`
- 추천 결과는 별도 저장하지 않고 시간 구간으로 계산한다.

## 미정 사항

- 예산의 기준: 인당 또는 모임 전체
- 표시 이름의 최초 설정 및 로그인 시 갱신 방식
- 시간 입력 단위, 겹치는 구간 처리
- 미입력 참여자 및 전원 공통 시간이 없는 경우의 추천 방식
- 초대 만료 기간, 확정 후 수정·참여 정책
- 회원 탈퇴·약속 삭제 시 관련 데이터 처리 정책
