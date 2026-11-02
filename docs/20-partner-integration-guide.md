# 20. 모의 본인확인 API 제휴사(이용기관) 연동 가이드

> 문서 성격: **인증 플랫폼(기관) 입장에서 이용기관 개발자에게 주는 연동 규격서**입니다.
> - 실습에서 이용기관은 서버이고, 기관은 로컬 모의 서버(`lab/mock-identity-provider`)입니다.
> - 구성은 카카오뱅크 인증서 공개 API 가이드의 목차를 참고했습니다. 공통 규격 → API별 요청·응답 → 결과코드 → 샘플 순서입니다.
> - **실습용 모의 규격이며 실제 본인확인 서비스가 아닙니다.**

| 항목 | 값 |
|---|---|
| 문서 버전 | v1.1 (2026-11-02) |
| 대상 API 버전 | `v1` |
| 변경 이력 | v1.0 최초 작성 / v1.1 결과 전자서명(`signed_data`) 추가, 등록된 callback URL만 허용(`MV9107`) |

## 1. 공통 규격

| 항목 | 규격 | 비고 |
|---|---|---|
| 프로토콜 | HTTP/1.1, 실습은 `http://localhost:18090` | 실제 서비스라면 HTTPS(TLS 1.2 이상)와 서버 IP 방화벽 등록이 필요 |
| 메서드 | 서버 간 API는 모두 `POST` | 결과 조회도 POST (참고한 공개 가이드와 동일) |
| 인코딩 | `Content-Type: application/json; charset=UTF-8` | |
| 인증 | 접근토큰을 `Authorization: Bearer {access_token}` 헤더로 전달 | 토큰 발급 API만 예외 |
| 일시 형식 | `yyyyMMddHHmmss`, 서버 로컬 시간(KST) | 예: `20261102231532` |
| 응답 봉투 | `{ "response_code", "message", "result" }` | **`response_code`로 처리 결과를 판단**하고, HTTP 상태는 보조로만 사용 |
| 멱등성 | 본인확인 요청은 `client_request_id` 기준으로 멱등 | 응답이 유실돼 다시 보내도 같은 거래를 돌려줌 |
| 개인정보 | 요청·응답 어디에도 이름·생년월일·휴대폰번호·CI·DI를 넣지 않음 | 실습 원칙 |
| 결과 무결성 | 거래 상태를 담은 응답에는 전자서명(`signed_data`)이 붙음 | §5 서명 검증 |

**표기 범례:** REQ는 M(필수)·O(선택)이고, LENGTH는 최대 길이입니다.

## 2. 연동 흐름

```mermaid
sequenceDiagram
    participant C as 이용기관 서버
    participant P as 인증 플랫폼(모의)
    participant U as 사용자 브라우저
    C->>P: ① 접근토큰 발급 (corp_id, credential)
    P-->>C: access_token (만료 일시)
    C->>P: ② 본인확인 요청 (client_request_id, client_callback_url)
    P-->>C: composite_key, standard_web_url, expire_dttm
    C-->>U: standard_web_url로 이동시킴
    U->>P: 표준창에서 인증 (성공/실패/취소)
    P-->>U: 303 → client_callback_url?verificationId={client_request_id}
    U->>C: 복귀 (결과값 없음)
    C->>P: ③ 결과 요청 (composite_key)
    P-->>C: response_code + result + signed_data
    C->>C: ④ 서명·인증서 검증 → client_request_id 대조 → 반영
```

## 3. API 상세

### 3.1 접근토큰 발급

`POST /mock-idp/v1/corp/auth`

**요청 파라미터**

| PARAMETER | TYPE | LENGTH | REQ | DESCRIPTION |
|---|---|---|---|---|
| corp_id | String | 50 | M | 이용기관 ID (제휴 시 발급, 실습 `IDV-LAB`) |
| credential | String | 255 | M | 이용기관 credential (제휴 시 별도 채널로 전달, 실습 값은 `application.yml`) |

**응답 파라미터**

| PARAMETER | TYPE | LENGTH | REQ | DESCRIPTION |
|---|---|---|---|---|
| response_code | String | 6 | M | 처리결과 코드 |
| message | String | 255 | M | 결과 메시지 |
| result.access_token | String | 64 | M | 접근토큰 |
| result.expire_dttm | String | 14 | M | 토큰 만료 일시. 실습 가정은 발급 후 1시간 |

**운영 권장:** 토큰은 만료 직전까지 캐시해서 재사용합니다. 다른 API가 `MV9102`를 응답하면 토큰을 1회 재발급한 뒤 다시 호출합니다.

```http
POST /mock-idp/v1/corp/auth HTTP/1.1
Host: localhost:18090
Content-Type: application/json

{"corp_id":"IDV-LAB","credential":"lab-mock-credential"}
```

```json
{"response_code": "MV0000", "message": "정상처리 완료",
 "result": {"access_token": "f097c0b50f66641e6b3699d9ef9dde70", "expire_dttm": "20261103001032"}}
```

### 3.2 본인확인 요청

`POST /mock-idp/v1/identifications`

**요청 파라미터**

| PARAMETER | TYPE | LENGTH | REQ | DESCRIPTION |
|---|---|---|---|---|
| corp_id | String | 50 | M | 이용기관 ID |
| client_request_id | String | 64 | M | 이용기관이 만든 요청 식별값. **멱등 키**이며 결과 서명 원문에도 들어감 (이 서비스는 UUID 사용) |
| client_callback_url | String | 255 | M | 인증 종료 후 돌아갈 URL. **제휴 시 등록한 URL과 정확히 일치해야 함** (아니면 `MV9107`) |
| req_service_name | String | 50 | O | 표준창에 표시할 서비스명. 기본값 `본인확인 실습` |
| loopback_param | Object | – | O | 이용기관이 넣은 값을 결과에 그대로 돌려줌 |

**응답 파라미터**

| PARAMETER | TYPE | LENGTH | REQ | DESCRIPTION |
|---|---|---|---|---|
| response_code | String | 6 | M | 처리결과 코드 |
| result.trx_key | String | 20 | M | 거래 키. 표준창 URL과 서명 원문에 사용 |
| result.composite_key | String | 24 | M | 결과 요청·취소할 때 Path에 넣는 키 |
| result.expire_dttm | String | 14 | M | 인증 만료 일시. 실습 가정은 접수 후 300초 |
| result.standard_web_url | String | 255 | M | 사용자를 보낼 표준창 URL |

```http
POST /mock-idp/v1/identifications HTTP/1.1
Host: localhost:18090
Authorization: Bearer f097c0b50f66641e6b3699d9ef9dde70
Content-Type: application/json

{"corp_id":"IDV-LAB","client_request_id":"8f6c3a6e-2a0c-4f43-9d0e-6b3c1f0e2d11",
 "client_callback_url":"http://localhost:8080/lab/identity-demo","req_service_name":"본인확인 실습"}
```

```json
{"response_code": "MV0000", "message": "정상처리 완료",
 "result": {"trx_key": "ec43bcdcc5f32980a8e1", "composite_key": "263ad5f435fa53da18072f17",
            "expire_dttm": "20261102231532",
            "standard_web_url": "http://localhost:18090/mock-idp/web/standard-auth?trx_key=ec43bcdcc5f32980a8e1"}}
```

### 3.3 결과 요청 (polling)

`POST /mock-idp/v1/identifications/{composite_key}/result` (Body 없음)

**응답 파라미터**

| PARAMETER | TYPE | LENGTH | REQ | DESCRIPTION |
|---|---|---|---|---|
| response_code | String | 6 | M | **결과 판단 기준** (§4) |
| result.trx_key | String | 20 | M | 거래 키 |
| result.client_request_id | String | 64 | M | 요청할 때 보낸 값. **이용기관은 자기 요청 ID와 일치하는지 반드시 대조** |
| result.status | String | 20 | M | `PENDING` `SUCCEEDED` `FAILED` `USER_CANCELLED` `CLIENT_CANCELLED` `EXPIRED` |
| result.expire_dttm | String | 14 | M | 인증 만료 일시 |
| result.completed_dttm | String | 14 | O | 종료 일시. 진행 중이면 null |
| result.loopback_param | Object | – | O | 요청할 때 보낸 값 그대로 |
| result.signed_data | String | 512 | M | 서명값 (Base64, SHA256withRSA) |
| result.signer_cert | String | 2048 | M | 서명자 인증서 (Base64 DER, X.509) |
| result.sign_alg | String | 20 | M | 서명 알고리즘. 현재는 `SHA256withRSA`만 사용 |

```json
{
  "response_code": "MV0000",
  "message": "정상처리 완료",
  "result": {
    "trx_key": "ec43bcdcc5f32980a8e1",
    "client_request_id": "8f6c3a6e-2a0c-4f43-9d0e-6b3c1f0e2d11",
    "status": "SUCCEEDED",
    "expire_dttm": "20261102231532",
    "completed_dttm": "20261102231032",
    "loopback_param": {},
    "signed_data": "Fqix2lVGtXk00HNcPHg5J34VqnmZffQcBtCbf2gH...(생략)",
    "signer_cert": "MIIDpjCCAo6gAwIBAgIUexYbV4xVeW0n5ki49E8L...(생략)",
    "sign_alg": "SHA256withRSA"
  }
}
```

**조회 주기 권장:** 사용자가 표준창에서 돌아오면 즉시 1회 조회하고, 그 뒤로는 2초 간격으로 조회합니다(실습 가정). `expire_dttm`이 지난 뒤에는 조회를 멈춥니다.

### 3.4 요청 취소

`POST /mock-idp/v1/identifications/{composite_key}/cancel` (Body 없음)

- 진행 중인 거래는 `CLIENT_CANCELLED`(`MV1004`)로 종료됩니다.
- **이미 종료된 거래는 바뀌지 않고 그 종료 상태를 그대로 응답합니다.** 예를 들어 이미 성공한 거래라면 `MV0000`이 옵니다.
- 응답 형식과 서명 규칙은 §3.3과 같습니다.

### 3.5 표준창 (브라우저)

| 항목 | 내용 |
|---|---|
| URL | `GET /mock-idp/web/standard-auth?trx_key={trx_key}` |
| 표시 | 상단에 "실습용 모의 인증이며 실제 본인확인이 아닙니다" 배너, 남은 시간 |
| 사용자 선택 | 인증 성공 / 인증 실패(불일치 시나리오) / 인증 취소 / 창 닫기만 하기 |
| 복귀 | `303 → {client_callback_url}?verificationId={client_request_id}` |
| 주의 | **복귀 URL에는 결과값이 없습니다.** 결과는 반드시 §3.3 서버 간 API로 확인합니다 |

## 4. 결과코드

| CODE | HTTP | DESCRIPTION | 서명 | 이용기관 권장 처리 |
|---|---|---|---|---|
| `MV0000` | 200 | 정상처리 완료 (인증 성공) | O | 서명 검증 후 성공 반영 |
| `MV0100` | 200 | 인증 결과 대기중 | O | 상태 유지, 다시 조회 |
| `MV1000` | 200 | 사용자 직접 취소 (표준창) | O | 취소로 확정 |
| `MV1001` | 200 | 사용자 응답 없음 (유효시간 경과) | O | 만료로 확정 |
| `MV1002` | 200 | 인증 실패 (모의: 가입자 정보 불일치) | O | 실패로 확정 |
| `MV1004` | 200 | 이용기관 요청으로 취소됨 | O | 취소로 확정 |
| `MV9003` | 503 | 서버 통신 오류 | – | **확정하지 않음**, 다시 시도 |
| `MV9102` | 401 | AccessToken 만료 또는 유효하지 않음 | – | 토큰 재발급 후 1회 재시도 |
| `MV9104` | 400 | 서비스 요청 파라미터 오류 | – | 요청 수정 (연동 오류) |
| `MV9106` | 406 | 존재하지 않는 트랜잭션 | – | 실패로 확정 (예: composite_key 오류, 기관 데이터 유실) |
| `MV9107` | 400 | 등록되지 않은 callback URL | – | 포털에서 callback URL 등록 상태 확인 |
| `MV9200` | 401 | 허용되지 않은 이용기관 코드 또는 credential | – | 제휴 정보 확인 |

**네트워크 오류, 타임아웃, 해석할 수 없는 코드는 '결과 미확정'입니다.** 인증 실패로 처리하지 마세요.

## 5. 결과 전자서명 검증 (이용기관 필수 구현)

**서명 원문** (구분자 `|`, UTF-8, 값이 null이면 빈 문자열):

```
MVSIG1|{corp_id}|{trx_key}|{client_request_id}|{response_code}|{status}|{completed_dttm}
```

예: `MVSIG1|IDV-LAB|ec43bcdcc5f32980a8e1|8f6c3a6e-2a0c-4f43-9d0e-6b3c1f0e2d11|MV0000|SUCCEEDED|20261102231032`

**검증 순서.** 하나라도 실패하면 결과를 반영하지 않고 '결과 미확정'으로 다룹니다.

1. `sign_alg`가 `SHA256withRSA`인지 확인
2. `signer_cert`를 X.509로 파싱하고, 기관이 미리 배포한 **Root CA 인증서(trust anchor)**로 발급 서명을 검증
3. 현재 시각에 Root CA와 서명자 인증서가 모두 유효기간 안인지 확인
4. 서명자 인증서가 CA 인증서가 아니고 `keyUsage`에 `digitalSignature`가 있는지 확인
5. 서명자 CN이 `Mock IdP Result Signing`인지 확인 (서명자 고정)
6. 서명 원문을 이용기관 쪽에서 직접 조립해 `signed_data`를 검증
7. `client_request_id`가 자기 요청 ID와 같은지 대조 (다른 거래의 결과가 섞여 들어오는 것 차단)

참고 구현은 `ResultSignatureVerifier.java`, `MockIdentityProviderClient#verifyIntegrity`입니다.

**배포물:**
- Root CA 인증서: `lab/pki/out/lab-root-ca.crt` (`lab/pki/generate-lab-pki.sh`로 생성)
- 서명 인증서 정보: 제휴사 포털에서 확인

**공개 가이드와 다른 점:** 참고한 공개 가이드는 PKCS#7 형식의 `signed_data`를 사용합니다. 이 실습은 학습을 위해 '원문 + 서명값 + 인증서'를 분리한 단순한 형식을 씁니다.

## 6. 테스트 환경과 장애 재현 (실습 전용)

| 목적 | 호출 |
|---|---|
| 장애 주입 | `POST /mock-idp/admin/faults` `{"mode": "...", "remaining": 0}` (0이면 해제할 때까지 계속) |
| mode 값 | `NONE` / `DOWN`(503) / `TIMEOUT`(6초 지연) / `DROP_AFTER_PROCESS`(처리 후 응답 유실) / `TAMPER`(서명 후 상태 변조) / `UNTRUSTED_SIGNER`(신뢰 체계 밖의 키로 서명) |
| 거래 상태 강제 | `POST /mock-idp/admin/identifications/{composite_key}/force` `{"status":"EXPIRED","override":false}` |
| 거래 목록 | `GET /mock-idp/admin/identifications` |
| 초기화 | `POST /mock-idp/admin/reset` |
| 제휴사 포털 | `GET /mock-idp/portal` ([21-partner-portal-plan.md](21-partner-portal-plan.md)) |

## 7. 연동 체크리스트 (이용기관 오픈 전)

- [ ] 토큰 캐시를 쓰고, `MV9102`가 오면 재발급 후 재시도한다
- [ ] `client_request_id`를 요청마다 새로 만들고, 재시도할 때는 같은 값을 쓴다
- [ ] callback URL이 포털에 등록된 값과 정확히 같다
- [ ] 결과는 서버 간 결과 요청으로만 확인한다. 복귀 URL의 값은 신뢰하지 않는다
- [ ] 서명 검증 7단계와 `client_request_id` 대조를 구현했다
- [ ] 네트워크 오류와 `MV9xxx` 오류를 인증 실패로 처리하지 않는다
- [ ] 종료된 거래는 다시 다른 상태로 바꾸지 않는다
- [ ] §6의 장애 모드 6가지로 시험했다
