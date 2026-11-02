# 7. 요구사항 ↔ API ↔ 테스트 대응표

테스트는 세 종류이며, 표의 표기는 아래와 같습니다.

| 표기 | 종류 | 실행 |
|---|---|---|
| **U** | 단위 (`IdentityVerificationStateTest`, `ResultSignatureVerifierTest`, `LabEnvironmentGuardTest`) | `./gradlew test` |
| **I** | 통합 (`IdentityVerificationIntegrationTest`, `AuthAndMemberTest`). 로컬 PostgreSQL 사용. 외부 기관은 Fake, 시계는 앞당길 수 있는 시계로 바꿔 실행 | `./gradlew test` |
| **E** | 실제 HTTP E2E (`lab/e2e/run_e2e.py`). 서버 ↔ 모의 서버 | `./lab/run-lab.sh --e2e` |

## 7.1 검증 기준별 대응

| # | 요구사항 | API | 코드 위치 | 테스트 |
|---|---|---|---|---|
| 1 | 테스트 회원으로 로그인 후 인증 요청 생성 | 테스트 로그인, A1 | `TestLoginService`, `IdentityVerificationService#start` | E S1 / I 전체 |
| 2 | 모의 성공 후 내 정보에 확인 완료와 완료 시각 반영 | A2, A4 | `IdentityVerificationTxService#applySuccess`, `Member#markIdentityVerified` | E S1 |
| 3 | 취소·실패·만료 시 미확인 유지 | A2, A3 | `applyProviderResult` | E S2 / I `expiresOnServiceSide`, `successAfterExpiryIsNotAccepted` |
| 4 | 다른 회원의 요청 접근 차단 | A2, A3 | `findByVerificationIdAndMemberId` | E S3 / I `otherMemberBlocked` |
| 5 | 반복 요청·반복 조회 시 중복 처리 방지 | A1, A2 | 부분 유니크 인덱스, `sync()`, `markIdentityVerified` | E S1·S7 / I `concurrentFinalization` / U `markVerifiedOnce` |
| 6 | 외부 서버 장애 후 재조회로 복구 | A2 | `sync()`의 미확정 처리 | E S4 / I `outageBeforeAndAfterGrace` |
| 7 | 만료·종료된 요청의 상태 역전 방지 | A2 | `IdentityVerification#finish` | E S2·S5 / U `noReversal` / I `cancelWhileProviderDown` |
| 8 | 탈퇴 회원에게 결과 미반영 | DELETE /members/me, A2 | `IdentityVerificationWithdrawalCleaner`, `applySuccess`의 활성 회원 검사 | E S6 / U `withdrawalResetsVerification` / I `AuthAndMemberTest#withdrawal` |
| 9 | 실습 기능이 실제 환경으로 새지 않음 | – | `LabEnvironmentGuard` | U `LabEnvironmentGuardTest` 5개 |
| 10 | 인증 기반 동작 (토큰으로만 회원 식별, 탈퇴 토큰 차단, 공통 오류 형식) | 전체 | `JwtAuthenticationFilter`, `SecurityConfig` | I `AuthAndMemberTest` 5개, E S3 |
| 11 | 외부 결과 위·변조와 신뢰할 수 없는 서명자 차단 | A2 | `ResultSignatureVerifier`, `verifyIntegrity` | U `ResultSignatureVerifierTest` 7개 / E S8 |

## 7.2 정책·예외별 추가 대응

| 정책·예외 | 테스트 |
|---|---|
| 클라이언트가 보낸 성공 값을 믿지 않음 | I `clientCannotClaimSuccess`, E S1 (복귀 URL에 결과값이 없는지 확인) |
| 버튼 연속 클릭과 동시 요청 | E S1 (연속), S7 (동시 5회) |
| 등록 응답 유실 후 재시도 멱등 | E S4 (`DROP_AFTER_PROCESS`) / I `registrationUnconfirmed` |
| 명시 취소와 화면 닫기 구분 | E S2 |
| 취소와 성공의 경합 (외부 기준) | I `successBeatsCancel` |
| 외부 장애 중 취소 (서비스 기준) | I `cancelWhileProviderDown` |
| 성공 후 앱으로 복귀하지 못함 → 다음 진입 때 반영 | I `successWithoutReturnIsRecoveredOnNextStart` |
| 세션 만료 후 같은 회원 재로그인 | E S6 |
| 이미 확인 완료된 회원의 추가 요청 | E S1 (409) |
| 등록되지 않은 callback URL 거부 | E S8 (`MV9107`) |
| 제휴사 포털 노출 | E S8 |
| 문서와 코드 일치 | `python3 lab/tools/check_docs.py` |
| 응답 시각과 저장 시각 일치 (취소 재호출 멱등) | I `timestampsMatchDatabasePrecision`, E S2 |

## 7.3 실제 실행 결과

| 구분 | 결과 |
|---|---|
| `./gradlew test` | **35개 전부 통과** (단위 18, 통합 17) |
| `./lab/run-lab.sh --e2e` | **52/52 PASS** (S1~S8) |
| `python3 lab/tools/check_docs.py` | 불일치 0건 |
| CI (GitHub Actions) | 문서 점검 → JUnit → E2E ([.github/workflows/ci.yml](../.github/workflows/ci.yml)). 첫 실행에서 E2E 1건 실패(아래 7.5) → 수정 후 통과 |

## 7.4 범위 밖 / 미검증

| 항목 | 이유 |
|---|---|
| 실제 소셜 로그인 | 이 저장소는 테스트 로그인만 제공 ([01 §1.3](01-origin-and-scope.md)) |
| 인증서 폐지(CRL/OCSP), PKCS#7 형식 | 범위 밖 ([41 §6](41-pki.md)) |
| 접근토큰 만료(`MV9102`) 후 재발급 경로 | 자동 테스트 없음. `MOCK_IDP_TOKEN_TTL_SECONDS=1`로 재현 가능 |
| 모의 서버 재시작(`MV9106`) 시 FAILED 처리 | 자동 테스트 없음 |
| 부하·다중 인스턴스 | 범위 밖 |

## 7.5 개발 중 테스트가 잡아낸 문제

- **탈퇴 변경이 저장되지 않던 버그 (직접 작성한 코드)**
  - 원인: 탈퇴 정리기의 벌크 UPDATE에 `@Modifying(clearAutomatically = true)`를 붙였습니다. 이 옵션이 영속성 컨텍스트를 비우면서, 탈퇴 트랜잭션이 잠가 둔 회원 엔티티가 분리됐습니다. 그래서 회원 상태 변경이 저장되지 않았습니다.
  - 발견: E2E의 "탈퇴 → 재로그인" 단계가 실패하면서 드러났습니다.
  - 조치: 옵션을 제거하고, 이유를 주석으로 남겼습니다(`IdentityVerificationRepository`).
- **Linux에서만 드러난 시각 정밀도 버그 (CI가 발견)**
  - 증상: 같은 요청을 두 번 취소하면 두 응답의 `endedAt`이 서로 달랐습니다. 멱등이어야 하는 응답이 달라진 것입니다.
  - 원인: Linux 시계는 나노초까지 줍니다. 첫 응답은 메모리에 있던 나노초 값을 그대로 돌려줬고, 두 번째 응답은 DB(`TIMESTAMP(6)`, 마이크로초까지 저장)에서 읽은 값을 돌려줬습니다. macOS 시계는 마이크로초까지만 주기 때문에 로컬에서는 드러나지 않았습니다.
  - 조치: 서버의 기준 시각을 마이크로초 단위로 잘랐습니다. 나노초 시계를 흉내 낸 회귀 테스트 `timestampsMatchDatabasePrecision`도 추가했습니다. 이 테스트는 수정 전에는 실패하고 수정 후에는 통과하는 것을 확인했습니다.
- **테스트 환경 간섭:** 실습 실행에서 남은 상태가 테스트에 영향을 줬습니다. 테스트 DB를 분리하고, 매 테스트 전에 상태를 초기화하도록 했습니다.
