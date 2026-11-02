# 6. 실행·검증 가이드

## 6.1 준비 (최초 1회)

필요한 것은 Java 21, PostgreSQL 16, Python 3, openssl, bash입니다. Docker나 Redis는 필요 없습니다.

macOS(Homebrew) 예시:

```bash
brew install openjdk@21 postgresql@16
```

```bash
brew services start postgresql@16
```

기본 설정은 PostgreSQL 포트를 **5434**로 가정합니다. 작성자의 PC에서 5432 포트를 다른 PostgreSQL이 쓰고 있었기 때문입니다.
- 5432를 쓰려면 `PGPORT=5432`로 실행하고, `application.yml`(또는 `SPRING_DATASOURCE_URL`)도 같이 바꾸세요.
- DB(`idvlab`, `idvlab_test`)와 role(`idvlab`)은 `./lab/run-lab.sh`가 없을 때 자동으로 만듭니다.
- 실습 PKI(Root CA와 결과 서명 인증서)는 `lab/pki/out/`에 자동 생성됩니다. 이 파일들은 커밋하지 않습니다.

## 6.2 실행 명령

| 명령 | 하는 일 |
|---|---|
| `./lab/run-lab.sh` | 모의 기관(18090)과 서버(8080)를 띄우고 대기합니다. Ctrl+C로 함께 종료합니다 |
| `./lab/run-lab.sh --e2e` | 서버 기동 → E2E 52개 항목 → 종료 |
| `./lab/run-lab.sh --test` | JUnit 테스트만 실행합니다 (DB만 필요) |
| `python3 lab/tools/check_docs.py` | 문서와 코드가 어긋나지 않는지 점검합니다 |
| `python3 lab/tools/generate_reference.py` | OpenAPI 발췌와 코드 카탈로그를 `docs/generated/`에 생성합니다 (서버 실행 중일 때) |

```bash
./lab/run-lab.sh
```

로그는 `build/lab-logs/app.log`와 `build/lab-logs/mock.log`에 남습니다.

> **안전장치:** `LabEnvironmentGuard`가 DB와 모의 기관 주소를 검사합니다. 둘 중 하나라도 localhost가 아니면 **DB에 연결하기 전에** 기동을 중단합니다.

## 6.3 Swagger에서 직접 실행해 볼 순서

1. http://localhost:8080/swagger-ui/index.html 에 접속합니다.
2. **실습용 테스트 로그인**: `POST /api/v1/auth/test-login`에 Body `{"testUserKey":"lab-user-a"}`를 보냅니다. 응답의 `data.accessToken`을 복사합니다.
3. 우측 상단 **Authorize**에 토큰을 붙여넣습니다. `Bearer ` 없이 토큰만 넣습니다.
4. **회원**: `GET /api/v1/members/me`를 실행해 `identityVerificationStatus: "UNVERIFIED"`를 확인합니다.
5. **본인확인**: `POST /api/v1/members/me/identity-verifications`를 실행합니다. **201**이 오면 `verificationId`와 `authUrl`을 복사합니다.
6. 같은 API를 한 번 더 실행합니다. **200 `IDENTITY_VERIFICATION_RESUMED`**와 같은 `verificationId`가 오면 중복 생성이 막힌 것입니다.
7. 브라우저 새 탭에서 `authUrl`을 엽니다. "실습용 모의 인증이며 실제 본인확인이 아닙니다" 배너가 보이면 **인증 성공으로 진행**을 누릅니다.
8. `GET .../{verificationId}`를 실행해 `status: "SUCCEEDED"`, `accountStatus: "VERIFIED"`를 확인합니다.
9. `GET /api/v1/members/me`를 다시 실행해 `VERIFIED`와 `identityVerifiedAt`을 확인합니다.
10. `POST .../identity-verifications`를 다시 실행하면 **409 `IDENTITY_ALREADY_VERIFIED`**가 옵니다.
11. **다른 회원 차단**: `lab-user-b`로 로그인하고 Authorize를 바꾼 뒤, 8번의 `verificationId`로 `GET`하면 **404**가 옵니다.
12. **취소**: lab-user-b로 5번을 하고 `POST .../{id}/cancel`을 실행하면 `CANCELLED / USER_CANCELLED`가 옵니다.

**장애와 위·변조 시연**

아래 명령으로 모의 기관을 장애 상태로 만든 뒤 8번을 실행하면 503이 옵니다. 해제(`NONE`)한 뒤 다시 실행하면 정상 결과가 옵니다.

```bash
curl -X POST localhost:18090/mock-idp/admin/faults -H 'Content-Type: application/json' -d '{"mode":"DOWN"}'
```

```bash
curl -X POST localhost:18090/mock-idp/admin/faults -H 'Content-Type: application/json' -d '{"mode":"NONE"}'
```

결과 변조는 아래 명령 뒤에 8번을 실행합니다. 502가 오고 계정은 바뀌지 않습니다.

```bash
curl -X POST localhost:18090/mock-idp/admin/faults -H 'Content-Type: application/json' -d '{"mode":"TAMPER","remaining":1}'
```

## 6.4 데모 화면과 제휴사 포털

**데모 화면** (http://localhost:8080/lab/identity-demo)
1. 테스트 회원으로 로그인합니다. 신규 회원이면 "본인확인은 선택이에요" 안내가 나옵니다.
2. 마이페이지에서 **본인확인하기**를 누릅니다.
3. 모의 인증 화면에서 결과를 고릅니다.
4. 데모 화면으로 돌아오면 결과를 자동으로 조회하고 마이페이지를 갱신합니다.

- **토큰 버리기** 버튼: 세션 만료를 재현합니다.
- 화면 하단 로그: 실제로 오간 요청과 응답 JSON이 쌓입니다.

**제휴사 포털** (http://localhost:18090/mock-idp/portal)

기관 입장에서 본 연동 정보, 서명 인증서, 거래 목록, 결과코드 현황을 확인할 수 있습니다.

## 6.5 응답 읽는 법

| 위치 | 읽는 법 |
|---|---|
| `status` / HTTP 코드 | 200·201 정상, 401 로그인 필요, 404 없음(또는 다른 회원의 요청), 409 이미 완료, 502 외부 응답의 서명 검증 실패, 503 외부 결과 확인 불가 |
| `code` | 화면 분기에 쓰는 안정적인 식별자. `message`는 바뀔 수 있음 |
| `data.status` | **이번 시도**의 상태 |
| `data.endReason` | 시도가 끝난 이유 ([03 §3.6](03-policy-and-exceptions.md#36-종료-사유-코드-endreason)) |
| `data.accountStatus` | **계정**의 상태. 시도가 실패해도 계정은 바뀌지 않음을 여기서 확인 |
| `data.authUrl` | PENDING일 때만 값이 있음 |

**502와 503은 "인증 실패"가 아닙니다.** "지금은 외부 결과를 확정할 수 없다"는 뜻이고, DB 상태는 그대로입니다.
