# 30. 본인확인 API 운영 매뉴얼 (실습 환경 기준)

> 대상: 서버 운영자, 그리고 장애 대응 당번.
> 범위: 선택형 본인확인 기능과 모의 인증기관 연동.
> 이 문서의 숫자는 **모두 실습용 가정**입니다.

## 1. 구성 요약

| 구성요소 | 주소·포트 | 역할 | 기동 방법 |
|---|---|---|---|
| 서버 | `localhost:8080` | 본인확인 API, 결과 검증과 반영 | `./gradlew bootRun` |
| 모의 인증기관 | `localhost:18090` | 토큰·요청·결과·취소 API, 표준창, 제휴사 포털 | `python3 lab/mock-identity-provider/server.py` |
| PostgreSQL 16 | `localhost:5434` / DB `idvlab` | 회원·인증 시도 저장 | `brew services start postgresql@16` |
| 실습 PKI | `lab/pki/out/` | Root CA, 결과 서명 키·인증서 | `lab/pki/generate-lab-pki.sh` |

한 번에 실행하려면 `./lab/run-lab.sh`를 씁니다. 상태 확인은 다음 두 명령으로 합니다.

```bash
curl -s localhost:8080/actuator/health
```

```bash
curl -s localhost:18090/health
```

## 2. 설정 항목

설정 파일은 `src/main/resources/application.yml`입니다. 값을 바꾸면 재기동해야 반영됩니다.

| 키 | 기본값 | 의미 | 바꿀 때 영향 |
|---|---|---|---|
| `lab.test-users` | lab-user-a,b,c | 테스트 로그인 허용 키 | 목록에 없는 키로 로그인하면 400 |
| `lab.identity-verification.attempt-ttl` | 5m | 시도 유효시간(서비스 기준) | 짧게 하면 만료가 늘어남(지표 M2), 길게 하면 PENDING이 오래 남음 |
| `lab.identity-verification.poll-interval` | 2s | 앱에 안내하는 조회 간격 | 짧게 하면 외부 호출량이 늘어남 |
| `lab.identity-verification.unconfirmed-grace` | 10m | 만료 후 외부 결과를 확인하지 못할 때 만료로 확정하기까지의 유예 | 짧게 하면 장애가 길어질 때 '실제로는 성공한' 시도가 만료로 처리될 위험 |
| `lab.identity-verification.callback-url` | `http://localhost:8080/lab/identity-demo` | 표준창에서 돌아올 주소 | **모의 기관 등록값과 다르면 모든 요청이 실패(`MV9107` → 앱에 503)** |
| `lab.mock-identity-provider.base-url` | `http://localhost:18090` | 기관 주소 | localhost가 아니면 안전장치가 기동을 막음 |
| `lab.mock-identity-provider.corp-id` | IDV-LAB | 이용기관 ID | 서명 원문에 포함됨 |
| `lab.mock-identity-provider.credential` | (더미) | 토큰 발급 credential | 틀리면 `MV9200` → 503 |
| `lab.mock-identity-provider.connect-timeout` | 2s | 연결 타임아웃 | |
| `lab.mock-identity-provider.response-timeout` | 3s | 응답 타임아웃 | 상태 조회 API 응답 시간의 상한이 됨 |
| `lab.mock-identity-provider.trust-anchor` | `file:lab/pki/out/lab-root-ca.crt` | 서명 검증 기준 Root CA | 파일이 없으면 **기동 실패**(의도한 동작) |
| `lab.mock-identity-provider.expected-signer-cn` | Mock IdP Result Signing | 허용하는 서명자 CN | 기관이 인증서 CN을 바꾸면 함께 바꿔야 함 |

## 3. 모니터링

### 3.1 감사 로그 (`IDV_AUDIT`)

형식은 `IDV_AUDIT event=<이벤트> verificationId=<id> key=value ...`입니다. 개인정보·토큰·서명값은 남기지 않습니다.

| event | 레벨 | 의미 | 대응 |
|---|---|---|---|
| `CREATED` | INFO | 시도 생성 | – |
| `PROVIDER_REGISTERED` | INFO | 기관 등록 성공 | – |
| `FINISHED` | INFO | 종료 확정 (status, reason, providerCode 포함) | 사유 분포를 지표 M2와 대조 |
| `FINISHED_BY_WITHDRAWAL` | INFO | 탈퇴로 진행 중 요청 취소 | – |
| `ACCOUNT_VERIFIED` | INFO | 계정 VERIFIED 반영(회원당 최초 1회) | – |
| `PROVIDER_UNAVAILABLE` | WARN | 기관 호출 실패 (phase=REGISTER/FETCH_RESULT/CANCEL) | 1분에 N건 이상이면 §4 장애 A |
| `INTEGRITY_FAILURE` | **ERROR** | 결과 서명이나 요청 대조 실패 (failure=사유) | **1건만 나와도 §4 장애 D** |

```bash
grep -o "IDV_AUDIT event=.*" build/lab-logs/app.log | awk '{print $2}' | sort | uniq -c
```

```bash
grep "IDV_AUDIT" build/lab-logs/app.log | grep "verificationId=<조회할 ID>"
```

### 3.2 운영 지표 SQL (`lab/ops/metrics.sql`)

```bash
PGPASSWORD=idvlab psql -h localhost -p 5434 -U idvlab -d idvlab -f lab/ops/metrics.sql
```

| ID | 지표 | 보는 이유 | 주의 신호 (가정) |
|---|---|---|---|
| M1 | 시도 퍼널과 성공률 | 기획 KPI (시도 대비 성공) | 성공률이 평소보다 크게 하락 |
| M2 | 종료 사유 분포 | 이탈 원인 구분 (사용자 취소 vs 기관 실패 vs 만료 vs 장애) | `UNCONFIRMED_AFTER_EXPIRY`, `REGISTRATION_UNCONFIRMED`가 생기면 장애 흔적 |
| M3 | 성공까지 걸린 시간 p50·p90 | 유효시간(5분)이 적절한지 판단 | p90이 유효시간에 가까워짐 |
| M4 | 계정 본인확인 비율 | 기능 도입 효과 | – |
| M5 | 유효시간이 지났는데 아직 PENDING | 장애나 조회 누락 감지 | **0건이 정상** |
| M6 | 일별 추이 | 추세 | – |

실습 DB에서 실행한 예(2026-11-02, E2E 데이터 기준이라 실제 사용 패턴이 아닙니다):
- M1: 시도 30, 성공 8 (26.7%)
- M3: p50 1.7초, p90 4.1초
- M5: 0건

### 3.3 모의 기관 쪽 확인

- 제휴사 포털: `http://localhost:18090/mock-idp/portal` (거래 목록, 결과코드 현황)
- JSON 목록: `GET /mock-idp/admin/identifications`

## 4. 장애 대응 런북

공통 원칙이 있습니다. **외부 장애 동안 시도 상태를 수동으로 바꾸지 않습니다.** 서버는 결과를 확인하지 못하면 PENDING을 유지합니다. 장애가 풀린 뒤 사용자가 다시 조회하면 저절로 복구됩니다.

### A. 기관 연결 실패·지연 (앱에 503 `IDENTITY_PROVIDER_UNAVAILABLE`)

1. 증상: `PROVIDER_UNAVAILABLE` 경고가 늘어납니다. 앱에는 503이 보입니다.
2. 확인: 아래 명령으로 기관 상태를 봅니다. 타임아웃이면 응답 지연, 연결 거부면 프로세스 중단입니다.

   ```bash
   curl -m 3 localhost:18090/health
   ```

3. 조치: 모의 기관을 재기동합니다. 운영이라면 기관 장애 공지를 확인하고 문의 채널로 연락합니다.
4. 사용자 영향: 진행 중인 시도는 유지됩니다. 앱은 "결과 확인이 지연되고 있어요"를 띄우고 자동으로 다시 조회합니다.
5. 장애가 유효시간 + 유예(15분)보다 길어지면 해당 시도는 `EXPIRED/UNCONFIRMED_AFTER_EXPIRY`로 확정됩니다. 사용자는 새로 요청하면 됩니다.
6. 복구 확인: M5가 0건인지, 503이 더 나오지 않는지 봅니다.

### B. 모든 요청 생성이 503 (기관은 정상)

- 원인 후보는 callback URL 불일치(`MV9107`)와 credential 오류(`MV9200`)입니다.
- 확인: 앱 로그에서 `[모의 본인확인] 요청 등록 실패 code=`를 찾고, 포털의 등록 callback URL과 §2 설정을 대조합니다.
- 조치: 설정을 맞춘 뒤 재기동합니다. 설정 변경 이력을 남깁니다.

### C. 기동 실패

| 로그 | 원인 | 조치 |
|---|---|---|
| `[LAB GUARD] ... 로컬 주소여야 합니다` | DB·기관 주소가 localhost가 아님 | 설정 확인. 의도한 차단입니다 |
| `결과 서명 검증용 Root CA를 읽지 못했습니다` | `lab/pki/out` 없음 | `lab/pki/generate-lab-pki.sh` 실행 |
| Flyway 오류 | DB 스키마가 꼬임 | 실습 DB라면 drop/create 후 재기동 |

### D. 결과 무결성 검증 실패 (`INTEGRITY_FAILURE`, 앱에 502)

1. **보안 이벤트로 다룹니다.** 사용자 계정에는 반영되지 않았으므로 즉시 피해는 없습니다.
2. `failure` 값에 따라 조치합니다.
   - `SIGNATURE_MISMATCH`: 전송 중 변조나 기관 버그가 의심됩니다. 기관에 즉시 통보하고 해당 시간대 로그를 보존합니다.
   - `UNTRUSTED_ISSUER` / `UNEXPECTED_SIGNER`: 기관이 인증서를 교체했는지 포털 공지로 확인합니다. 공지되지 않은 교체라면 사칭으로 의심합니다.
   - `CERTIFICATE_NOT_VALID_NOW`: 인증서가 만료됐습니다. 기관에 교체 요청을 하고, 서버 시계(NTP)도 확인합니다.
   - `REQUEST_MISMATCH`: 다른 거래의 결과가 들어왔습니다. 기관 측 버그 또는 바꿔치기 시도입니다.
3. 검증 로직을 끄는 식의 우회는 하지 않습니다.

### E. 사용자 문의: "인증했는데 미확인으로 나와요"

1. 회원 ID로 최근 시도를 조회합니다.

   ```sql
   select verification_id, status, end_reason, created_at, ended_at
   from identity_verifications where user_id = ? order by created_at desc limit 5;
   ```

2. 판단 기준

| 조회 결과 | 설명 | 안내 |
|---|---|---|
| `PENDING` | 앱이 결과를 조회하지 않은 상태 | 앱에서 '본인확인하기'를 다시 누르면 반영됨(이미 성공했다면 409와 함께 완료) |
| `EXPIRED/SUCCEEDED_AFTER_EXPIRY` | 유효시간 이후 성공 | 다시 시도하도록 안내 |
| `CANCELLED/USER_CANCELLED_PROVIDER_UNCONFIRMED` | 장애 중에 취소 버튼을 누름 | 다시 시도하도록 안내 |
| 그 밖 | 감사 로그로 추적 | `grep verificationId=...` |

3. DB를 직접 고쳐 VERIFIED로 만들지 않습니다. 외부 결과로 확인되지 않은 확인 완료는 정책 위반입니다.

### F. 오래 남은 PENDING (M5 > 0)

- 사용자가 다시 조회하지 않아서 생긴 경우라면 정상입니다. 다음 조회나 요청 때 자동으로 확정됩니다.
- 운영 개선 아이디어로, 만료된 PENDING을 주기적으로 정리하는 스케줄러를 기획했지만 **이번에는 구현하지 않았습니다**([15-decision-log.md](15-decision-log.md) ADR-07).

## 5. 정기 작업

| 주기 | 작업 |
|---|---|
| 매일 | M1·M2·M5 확인, `INTEGRITY_FAILURE` 0건 확인 |
| 매주 | M3로 유효시간이 적절한지 검토 |
| 인증서 만료 60일 전 (가정) | 기관 인증서 교체 일정 확인. 실습 인증서 유효기간은 1년이므로 만료되면 `FORCE=1 lab/pki/generate-lab-pki.sh` 실행 |
| 변경 배포 전 | `./lab/run-lab.sh --test`, `--e2e`, `python3 lab/tools/check_docs.py` |

## 6. 데이터 보관·파기

| 데이터 | 보관 | 파기 |
|---|---|---|
| `identity_verifications` | 시도 이력(식별자·상태·시각만 있음) | 회원이 탈퇴하면 진행 중 건은 취소로 확정. 이력 행의 보관 기간은 미정입니다(정책 결정 필요, [40-compliance-analysis.md](40-compliance-analysis.md) 참고) |
| `members.identity_*` | 계정 상태 | 탈퇴할 때 초기화 |
| 감사 로그 | 로그 보관 정책을 따름 | – |

## 7. 변경 관리

1. 코드나 설정을 변경합니다.
2. `./lab/run-lab.sh --test`(JUnit)와 `--e2e`(52개 항목)를 실행합니다.
3. `python3 lab/tools/generate_reference.py`로 생성 문서를 갱신합니다(서버 실행 중이어야 함).
4. `python3 lab/tools/check_docs.py`로 문서와 코드가 어긋나지 않는지 확인합니다.
5. 변경 이력을 [20-partner-integration-guide.md](20-partner-integration-guide.md) 상단에 남깁니다(기관 API를 바꾼 경우).
