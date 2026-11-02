# 5. 데이터 모델

마이그레이션 파일은 `src/main/resources/db/migration/V1__init_schema.sql`입니다. Flyway가 적용하며, `ddl-auto: validate` 설정으로 엔티티와 스키마가 일치하는지 기동할 때 확인합니다.

## 5.1 `members` (실습용 최소 회원 + 계정 단위 본인확인 상태)

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| `id` | BIGSERIAL | PK | |
| `login_key` | VARCHAR(80) | UNIQUE, NOT NULL | 테스트 로그인 키. 탈퇴하면 `WITHDRAWN_{uuid}`로 바꿔 같은 키로 다시 가입할 수 있게 함 |
| `name`, `email` | VARCHAR | NOT NULL | 테스트 값. **본인확인 결과로 덮어쓰지 않음** |
| `status` | VARCHAR(20) | CHECK (`ACTIVE`,`WITHDRAWN`) | 탈퇴한 회원의 토큰은 인증 필터에서 거부 |
| `identity_verification_status` | VARCHAR(20) | NOT NULL, DEFAULT `'UNVERIFIED'`, CHECK | **계정 본인확인 상태** |
| `identity_verified_at` | TIMESTAMP(6) | NULL | **본인확인 완료 시각** (서버가 외부 결과를 확인·반영한 시각) |
| `withdrawn_at` | TIMESTAMP(6) | NULL | 탈퇴 시각 |
| `created_at`, `updated_at` | TIMESTAMP(6) | | 자동 기록 |

엔티티 메서드:
- `Member#markIdentityVerified`: 이미 VERIFIED면 아무것도 바꾸지 않습니다. 그래서 같은 결과가 두 번 반영되지 않습니다.
- `Member#withdraw`: 개인정보와 본인확인 상태를 파기합니다.

## 5.2 `identity_verifications` (개별 인증 시도)

| 컬럼 | 타입 | 제약 | 요구사항 대응 |
|---|---|---|---|
| `id` | BIGSERIAL | PK | 내부 키. 외부에 노출하지 않음 |
| `verification_id` | VARCHAR(36) | UNIQUE, NOT NULL | **서비스 내부 요청 식별값**. API Path로 쓰고, 외부 기관에는 `client_request_id`(멱등 키)로 전달 |
| `member_id` | BIGINT | FK → members, NOT NULL | **요청한 회원** |
| `provider` | VARCHAR(20) | NOT NULL | 외부 기관 구분 (`MOCK`) |
| `provider_transaction_id` | VARCHAR(64) | UNIQUE(provider, 이 컬럼) | **외부 기관 요청 식별값** (`composite_key`). 등록 응답이 유실되면 NULL |
| `auth_url` | VARCHAR(500) | | 진행 중 요청에 다시 들어갈 때 쓰는 인증 화면 주소 |
| `status` | VARCHAR(20) | NOT NULL, CHECK | **진행 상태** (PENDING/SUCCEEDED/FAILED/CANCELLED/EXPIRED) |
| `end_reason` | VARCHAR(50) | | **종료 사유 코드** |
| `expires_at` | TIMESTAMP(6) | NOT NULL | **만료 시각** (서비스 기준) |
| `ended_at` | TIMESTAMP(6) | | **종료 시각** |
| `created_at`, `updated_at` | TIMESTAMP(6) | | **생성 시각** |

**중복 요청 제어**
- `uk_identity_verifications_member_pending`은 `(member_id) WHERE status = 'PENDING'` 조건의 부분 유니크 인덱스입니다. 회원 한 명에게 진행 중인 요청이 2건 생기는 것을 DB가 최종적으로 막습니다.
- `idx_identity_verifications_member_created`는 회원별 이력 조회용 인덱스입니다.

**수집하지 않는 것:** CI, DI, 주민등록번호, 실명, 생년월일, 휴대폰번호. 외부 기관에서 받는 값도 상태와 시각뿐입니다.

## 5.3 설정·키 파일

| 파일 | 커밋 | 내용 |
|---|---|---|
| `src/main/resources/application.yml` | O | localhost DB와 모의 기관 주소, 실습용 가정 수치, **더미** secret |
| `src/test/resources/application-test.yml` | O | 테스트 DB `idvlab_test`, 테스트 Root CA 경로 |
| `src/main/resources/META-INF/spring.factories` | O | `LabEnvironmentGuard` 등록 |
| `lab/pki/out/*` | **X** | 실행용 키와 인증서. `run-lab.sh`가 생성 |
| `build/pki-test/*` | **X** | 테스트용 키와 인증서. Gradle `generateTestPki`가 생성 |
