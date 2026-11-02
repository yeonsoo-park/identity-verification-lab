# 4. API 명세

## 4.1 추가·변경 API 목록

### A. 앱 ↔ 서버

| # | 구분 | 메서드·경로 | 프로필 | 역할 |
|---|---|---|---|---|
| A1 | 신규 | `POST /api/v1/members/me/identity-verifications` | lab | 본인확인 요청 생성 (진행 중이면 재사용) |
| A2 | 신규 | `GET /api/v1/members/me/identity-verifications/{verificationId}` | lab | 진행 상태 조회 + 외부 결과 확인 |
| A3 | 신규 | `POST /api/v1/members/me/identity-verifications/{verificationId}/cancel` | lab | 명시적 취소 |
| A4 | 확장 | `GET /api/v1/members/me` | – | 내 정보 응답에 `identityVerificationStatus`, `identityVerifiedAt` 포함 |
| A5 | 신규(실습) | `POST /api/v1/auth/test-login` | lab | 실습용 테스트 회원 로그인 |
| A6 | 신규(실습) | `GET /lab/identity-demo` | lab | 최소 데모 화면 (Swagger 숨김) |

### B. 서버 ↔ 모의 인증기관 (`http://localhost:18090`)

| # | 메서드·경로 | 역할 | 참고한 카카오뱅크 공개 API 구조 |
|---|---|---|---|
| B1 | `POST /mock-idp/v1/corp/auth` | 접근토큰 발급 | 접근토큰발급 `/api/v1/corp/auth` |
| B2 | `POST /mock-idp/v1/identifications` | 본인확인 요청 등록 | 본인확인요청 `/api/b2b/v1/identification` |
| B3 | `POST /mock-idp/v1/identifications/{composite_key}/result` | 결과 요청 (polling) | 결과요청 `/api/b2b/v1/polling/mandatory/{composite_key}` |
| B4 | `POST /mock-idp/v1/identifications/{composite_key}/cancel` | 이용기관 요청 취소 | (공개 가이드에 없음, 실습용 추가) |
| B5 | `GET /mock-idp/web/standard-auth?trx_key=` | 모의 인증 화면 (브라우저) | 표준창 `standard_web_url` |
| B6 | `POST /mock-idp/admin/*` | 장애 주입, 상태 강제 변경, 초기화 (테스트 전용) | 없음 (실습 전용) |

## 4.2 설계 선택: 외부 결과 확인을 상태 조회(A2)에 포함한 이유

**선택:** 별도의 `/confirm` 엔드포인트를 만들지 않았습니다. `GET .../{verificationId}` 안에서 "PENDING이면 외부에 물어보고 반영"합니다.

| 고려 사항 | 판단 |
|---|---|
| 클라이언트 단순성 | 앱은 "돌아왔을 때"와 "주기적으로" 모두 같은 API 하나만 부르면 됩니다. 확인과 조회를 나누면 앱이 순서를 지켜야 하고, 실수로 조회만 하면 상태가 영원히 PENDING처럼 보입니다 |
| 클라이언트가 성공을 주장할 여지 | `/confirm`에 Body를 받기 시작하면 "성공 값을 전달"하는 구조로 흐르기 쉽습니다. A2는 Body가 없고, 판단 근거는 서버가 외부에서 직접 조회한 값뿐입니다 |
| 멱등성 | 결과는 한 번 확정되면 바뀌지 않습니다. A2를 몇 번 호출해도 최종 결과가 같습니다. 그래서 상태를 바꾸는 부수효과가 있어도 GET의 "반복해도 안전" 성질을 지킵니다 |
| 외부 기관 구조와의 대응 | 카카오뱅크 공개 가이드도 결과 확인을 "결과 요청(polling)" API로 이용기관이 조회하는 방식입니다. A2가 이를 감싸는 형태입니다 |
| 기존 프로젝트 관례 | 기존 코드에도 조회 성격의 엔드포인트가 상태를 갱신하는 사례가 있습니다(`PATCH .../views`). 다만 이번에는 반복해도 결과가 같다는 점을 근거로 GET을 선택했습니다 |
| 단점 | GET이 외부 호출을 일으켜 응답 시간이 외부에 의존합니다(최대 약 3초). 503을 "실패 아님, 재조회"로 정의해 보완했습니다 |

## 4.3 A1. 본인확인 요청 생성

| 항목 | 내용 |
|---|---|
| 호출 주체·시점 | 앱. ① 가입 직후 선택형 안내에서 "지금 하기" ② 마이페이지 "본인확인하기" ③ 503 이후 재시도 |
| 로그인 | 필요 (`Authorization: Bearer {accessToken}`) |
| Path / Query / Body | 없음. 회원은 토큰으로만 식별 |
| 중복 호출 | 진행 중 요청이 있으면 그 요청을 200 `RESUMED`로 반환 (동시 호출 포함, 회원당 PENDING 최대 1건) |
| 재시도 | 503 후 같은 API를 다시 부르면 같은 verificationId로 외부에 재등록 (멱등) |

**응답 `data` (IdentityVerificationResponse)** — A1·A2·A3 공통

| 필드 | 타입 | 항상 존재 | 허용값·설명 |
|---|---|---|---|
| `verificationId` | string(UUID) | O | 서비스 내부 요청 식별값. 조회·취소의 Path에 사용 |
| `status` | string | O | `PENDING` `SUCCEEDED` `FAILED` `CANCELLED` `EXPIRED` |
| `endReason` | string \| null | O | 종료 사유([3.6](03-policy-and-exceptions.md#36-종료-사유-코드-endreason)). PENDING·SUCCEEDED면 null |
| `authUrl` | string \| null | O | 외부 인증 화면 URL. PENDING이고 외부 등록이 끝났을 때만 값 있음 |
| `expiresAt` | string(LocalDateTime) | O | 이 시도의 서비스 기준 유효시간 |
| `endedAt` | string \| null | O | 종료 확정 시각 |
| `pollIntervalSeconds` | number | O | 권장 조회 간격(초) — 실습 가정 2 |
| `accountStatus` | string | O | 응답 시점의 계정 상태 `UNVERIFIED` `VERIFIED` |
| `identityVerifiedAt` | string \| null | O | 계정 본인확인 완료 시각 |

**응답 예시**

```json
// 201 새 요청
{ "status": 201, "code": "IDENTITY_VERIFICATION_CREATED", "message": "본인확인 요청을 생성했습니다.",
  "data": { "verificationId": "b73f1747-a9d5-4ca9-a87e-e2e28d6ad755", "status": "PENDING", "endReason": null,
            "authUrl": "http://localhost:18090/mock-idp/web/standard-auth?trx_key=2e86d40619af6ee99554",
            "expiresAt": "2026-11-02T22:48:56.23625", "endedAt": null, "pollIntervalSeconds": 2,
            "accountStatus": "UNVERIFIED", "identityVerifiedAt": null } }
// 200 진행 중 요청 재사용
{ "status": 200, "code": "IDENTITY_VERIFICATION_RESUMED", "message": "진행 중인 본인확인 요청을 이어서 진행합니다.", "data": { "...": "위와 같은 verificationId" } }
```

| HTTP | code | 상황 | 화면 동작 |
|---|---|---|---|
| 201 | `IDENTITY_VERIFICATION_CREATED` | 새 요청 | `authUrl`로 이동 |
| 200 | `IDENTITY_VERIFICATION_RESUMED` | 진행 중 요청 재사용 | `authUrl`로 이동 (이어서 진행) |
| 401 | `UNAUTHORIZED` | 토큰 없음·만료·탈퇴 | 로그인 화면 |
| 409 | `IDENTITY_ALREADY_VERIFIED` | 이미 확인 완료 (직전 요청이 지금 막 성공으로 확인된 경우 포함) | 내 정보 새로고침 → '본인확인 완료' 표시 |
| 503 | `IDENTITY_PROVIDER_UNAVAILABLE` | 외부 등록 실패·시간 초과 | "잠시 후 다시" 안내. 요청은 유지되므로 다시 누르면 이어서 진행 |

## 4.4 A2. 진행 상태 조회 (외부 결과 확인 포함)

| 항목 | 내용 |
|---|---|
| 호출 주체·시점 | 앱. ① 외부 인증 화면에서 복귀 직후 ② PENDING인 동안 `pollIntervalSeconds` 간격 ③ "결과 다시 확인" 버튼 |
| 로그인 | 필요 |
| Path | `verificationId` (string, 필수) — A1에서 받은 값 |
| Body | 없음 (클라이언트가 결과를 보내는 경로 자체가 없음) |
| 중복 호출 | 안전. 종료된 요청은 외부를 다시 부르지 않고 저장된 값 반환, 계정 반영은 최초 1회 |
| 재시도 | 503이면 같은 요청을 그대로 재조회. 유효시간 + 유예 10분이 지나도록 확인 불가면 EXPIRED로 확정됨 |
| 접근 차단 | `verificationId + 토큰의 userId`로만 조회 → 다른 회원 요청은 404 |

| HTTP | code | 상황 | 화면 동작 |
|---|---|---|---|
| 200 | `IDENTITY_VERIFICATION_FETCHED` | `status=PENDING` | "인증을 기다리는 중" + 간격 후 재조회 |
| 200 | 〃 | `SUCCEEDED` | "본인확인 완료" + 내 정보 새로고침 |
| 200 | 〃 | `FAILED`/`CANCELLED`/`EXPIRED` | 사유 안내, 계정은 미확인. '본인확인하기'로 새 요청 가능 |
| 401 | `UNAUTHORIZED` | 세션 만료 | 같은 회원으로 재로그인 후 같은 verificationId 재조회 |
| 404 | `IDENTITY_VERIFICATION_NOT_FOUND` | 없는 요청 / 다른 회원의 요청 | 저장해 둔 id 삭제, 마이페이지로 |
| 502 | `IDENTITY_RESULT_INTEGRITY_FAILED` | 외부 결과의 전자서명 검증 실패 또는 다른 요청의 결과 (**실패 아님**, DB 상태 변화 없음, 보안 이벤트) | 503과 같은 문구로 자동 재조회 |
| 503 | `IDENTITY_PROVIDER_UNAVAILABLE` | 외부 결과 확인 불가 (**실패 아님**, DB 상태 변화 없음) | "결과 확인이 지연되고 있어요" + 자동 재조회 |

```json
// 200 외부 화면에서 취소된 경우
{ "status": 200, "code": "IDENTITY_VERIFICATION_FETCHED", "message": "본인확인 진행 상태 조회에 성공했습니다.",
  "data": { "verificationId": "8f6c...", "status": "CANCELLED", "endReason": "PROVIDER_USER_CANCELLED", "authUrl": null,
            "expiresAt": "2026-11-02T22:45:00", "endedAt": "2026-11-02T22:41:03", "pollIntervalSeconds": 2,
            "accountStatus": "UNVERIFIED", "identityVerifiedAt": null } }
// 404
{ "status": 404, "code": "IDENTITY_VERIFICATION_NOT_FOUND", "message": "본인확인 요청을 찾을 수 없습니다." }
// 503
{ "status": 503, "code": "IDENTITY_PROVIDER_UNAVAILABLE", "message": "인증기관 응답을 확인하지 못했습니다. 잠시 후 다시 확인해주세요." }
```

## 4.5 A3. 본인확인 요청 취소

| 항목 | 내용 |
|---|---|
| 호출 주체·시점 | 앱. 사용자가 서비스 화면의 '취소'를 **명시적으로** 눌렀을 때만. 화면 닫기·뒤로가기는 호출하지 않음 |
| 로그인 | 필요 |
| Path | `verificationId` (필수) |
| Body | 없음 |
| 응답 | 항상 처리 후 **최종 상태**. 취소보다 외부 성공이 먼저였다면 `SUCCEEDED` |
| 중복 호출 | 멱등. 이미 종료된 요청이면 현재 상태 그대로 200 |
| 외부 장애 시 | 서비스 기준으로 `CANCELLED`(`USER_CANCELLED_PROVIDER_UNCONFIRMED`) 확정 — 이후 외부 성공은 반영하지 않음 |

| HTTP | code | 화면 동작 |
|---|---|---|
| 200 | `IDENTITY_VERIFICATION_CANCEL_PROCESSED` | `status`에 따라 "취소했어요" 또는 (드물게) "완료됐어요" |
| 401 | `UNAUTHORIZED` | 로그인 화면 |
| 404 | `IDENTITY_VERIFICATION_NOT_FOUND` | 없는 요청 / 다른 회원의 요청 |

## 4.6 A4. 내 정보 조회

| 항목 | 내용 |
|---|---|
| 필드 | `name`, `email`, `identityVerificationStatus`(`UNVERIFIED`\|`VERIFIED`), `identityVerifiedAt`(LocalDateTime\|null) |
| 동작 | 계정을 조회만 합니다. 외부 호출이나 진행 중 시도의 동기화는 하지 않습니다 |
| 하위 호환 | 팀 서비스에 붙였을 때는 기존 응답에 **필드 2개만 추가**했습니다. 그래서 기존 클라이언트에 영향이 없었습니다 ([01 §1.2](01-origin-and-scope.md)) |

```json
{ "status": 200, "code": "MEMBER_INFO_FETCHED", "message": "회원 정보 조회에 성공했습니다.",
  "data": { "name": "실습회원-lab-user-a", "email": "lab-user-a@lab.invalid",
            "identityVerificationStatus": "VERIFIED", "identityVerifiedAt": "2026-11-02T22:43:56.57572" } }
```

마이페이지 분기: `UNVERIFIED`이면 '본인확인하기' 버튼, `VERIFIED`이면 '본인확인 완료'와 `identityVerifiedAt`을 보여줍니다.

## 4.7 A5. 실습용 테스트 로그인

| 항목 | 내용 |
|---|---|
| 경로 | `POST /api/v1/auth/test-login` (소셜 로그인 대체용 실습 API) |
| Body | `{ "testUserKey": "lab-user-a" }` — 허용값 `lab-user-a`, `lab-user-b`, `lab-user-c` (필수, 문자열) |
| 응답 | 200 `TEST_LOGIN_SUCCESS`, `data`: `accessToken`, `name`, `isNewUser` (true면 가입 직후 선택형 본인확인 안내) |
| 동작 | 로그인 키로 회원 조회, 없으면 생성(`MemberService#findOrJoin`, 동시 첫 로그인 대비) → 액세스 토큰 발급 |
| 오류 | 400 `TEST_USER_NOT_ALLOWED`, 400 `INVALID_INPUT_VALUE`(키 누락) |

## 4.8 B. 서버 ↔ 모의 인증기관 API

공통 응답 봉투 (카카오뱅크 공개 가이드의 `response_code`/`result` 구조 참고):

```json
{ "response_code": "MV0000", "message": "정상처리 완료", "result": { ... } }
```

### B1. 접근토큰 발급 — `POST /mock-idp/v1/corp/auth`

| 요청 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `corp_id` | string | M | 이용기관 ID (실습 `IDV-LAB`) |
| `credential` | string | M | 실습용 credential (`application.yml`) |

응답 `result`: `access_token`, `expire_dttm`(yyyyMMddHHmmss). 서버는 만료 30초 전까지 메모리에 캐시하고, `MV9102`를 받으면 1회 재발급 후 재시도합니다.

### B2. 본인확인 요청 — `POST /mock-idp/v1/identifications`

Header `Authorization: Bearer {access_token}` (공개 가이드는 Body에 토큰을 넣지만, 로그·Body 노출을 줄이려 헤더로 옮김)

| 요청 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `corp_id` | string | M | 이용기관 ID |
| `client_request_id` | string | M | 서비스의 `verificationId`. **이 값 기준으로 멱등** (응답 유실 후 재요청 시 같은 트랜잭션 반환) |
| `client_callback_url` | string | M | 인증 화면 종료 후 돌아갈 서비스 화면. **기관에 등록된 URL만 허용**(아니면 `MV9107`) |
| `req_service_name` | string | O | 인증 화면에 표시할 서비스명 |

이름·생년월일·휴대폰번호 같은 개인정보는 **보내지 않습니다** (공개 가이드의 `name/phone/birthday` 대응 필드 없음).

응답 `result`: `trx_key`, `composite_key`(서비스의 `provider_transaction_id`), `expire_dttm`, `standard_web_url`(서비스의 `authUrl`)

### B3. 결과 요청 — `POST /mock-idp/v1/identifications/{composite_key}/result`

응답 `result`: `trx_key`, `client_request_id`, `status`, `expire_dttm`, `completed_dttm`, `signed_data`, `signer_cert`, `sign_alg`.
- 서버는 **전자서명과 `client_request_id` 대조를 통과한 경우에만** `response_code`로 의미를 판단합니다.
- 서명 규격과 검증 순서는 [20 §5](20-partner-integration-guide.md#5-결과-전자서명-검증-이용기관-필수-구현)에 있습니다.

| response_code | HTTP | 의미 | 서비스 반영 |
|---|---|---|---|
| `MV0000` | 200 | 인증 성공 | SUCCEEDED (+유효시간·탈퇴 검사) |
| `MV0100` | 200 | 대기 중 | 유지 (서비스 유효시간 지났으면 EXPIRED) |
| `MV1000` | 200 | 사용자 취소 | CANCELLED `PROVIDER_USER_CANCELLED` |
| `MV1001` | 200 | 시간 내 미진행 | EXPIRED `PROVIDER_EXPIRED` |
| `MV1002` | 200 | 인증 실패(모의) | FAILED `PROVIDER_REJECTED` |
| `MV1004` | 200 | 이용기관 요청 취소됨 | CANCELLED `USER_CANCELLED` |
| `MV9106` | 406 | 존재하지 않는 트랜잭션 | FAILED `PROVIDER_TRANSACTION_NOT_FOUND` |
| `MV9102` | 401 | 토큰 만료 | 토큰 재발급 후 1회 재시도 |
| `MV9003` | 503 | 서버 통신 오류 | **상태 유지**, 앱에 503 |
| `MV9107` | 400 | 등록되지 않은 callback URL (등록 단계) | 앱에 503, 운영 매뉴얼 런북 B |
| 서명 검증 실패 | – | 위·변조, 신뢰할 수 없는 서명자, 다른 요청의 결과 | **상태 유지**, 앱에 502 |
| 그 외 / 연결 실패 / 타임아웃 | — | 의미 불명 | **상태 유지**, 앱에 503 |

### B4. 이용기관 요청 취소 — `POST /mock-idp/v1/identifications/{composite_key}/cancel`

PENDING이면 `CLIENT_CANCELLED`(`MV1004`)로 바꾸고, 이미 종료됐으면 **그 종료 상태 코드를 그대로** 응답합니다 (예: 이미 성공 → `MV0000`).

### B5. 모의 인증 화면 — `GET /mock-idp/web/standard-auth?trx_key=`

상단에 "실습용 모의 인증이며 실제 본인확인이 아닙니다" 배너. 버튼: 인증 성공 / 인증 실패 / 인증 취소 / 창 닫기만 하기. 처리 후 `303 → {client_callback_url}?verificationId=...` — **결과값을 URL에 싣지 않습니다**.

### B6. 테스트 전용 관리 API

| 경로 | Body | 용도 |
|---|---|---|
| `POST /mock-idp/admin/faults` | `{"mode":"NONE\|DOWN\|TIMEOUT\|DROP_AFTER_PROCESS\|TAMPER\|UNTRUSTED_SIGNER","remaining":0}` | 장애 주입 (`remaining` 0 = 해제 전까지 계속) |
| `POST /mock-idp/admin/identifications/{key}/force` | `{"status":"EXPIRED", "override":false}` | 만료 등 상태 강제. `override:true`는 종료된 건도 뒤집어 '상태 충돌' 재현 |
| `GET /mock-idp/admin/identifications` | — | 모의 기관이 가진 트랜잭션 목록 |
| `POST /mock-idp/admin/reset` | — | 초기화 |
