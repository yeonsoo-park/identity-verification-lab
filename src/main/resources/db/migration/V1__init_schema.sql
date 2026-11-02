-- 가입 후 선택형 본인확인 실습 스키마
-- CI·DI·주민등록번호·실명·생년월일·전화번호 등 실제 식별정보는 저장하지 않는다.

-- 1) 회원 (실습용 최소 모델) + 계정 단위 본인확인 상태
CREATE TABLE members (
    id                           BIGSERIAL PRIMARY KEY,
    login_key                    VARCHAR(80)  NOT NULL,
    name                         VARCHAR(50)  NOT NULL,
    email                        VARCHAR(120) NOT NULL,
    status                       VARCHAR(20)  NOT NULL,
    identity_verification_status VARCHAR(20)  NOT NULL DEFAULT 'UNVERIFIED',
    identity_verified_at         TIMESTAMP(6),
    withdrawn_at                 TIMESTAMP(6),
    created_at                   TIMESTAMP(6) NOT NULL,
    updated_at                   TIMESTAMP(6),
    CONSTRAINT uk_members_login_key UNIQUE (login_key),
    CONSTRAINT members_status_check CHECK (status IN ('ACTIVE', 'WITHDRAWN')),
    CONSTRAINT members_identity_verification_status_check
        CHECK (identity_verification_status IN ('UNVERIFIED', 'VERIFIED'))
);

-- 2) 개별 인증 시도 단위 상태
CREATE TABLE identity_verifications (
    id                      BIGSERIAL PRIMARY KEY,
    verification_id         VARCHAR(36)  NOT NULL,   -- 내부 요청 식별값 (외부 기관에 client_request_id로 전달, 멱등 키)
    member_id               BIGINT       NOT NULL,   -- 요청한 회원 (로그인 토큰에서만 결정)
    provider                VARCHAR(20)  NOT NULL,   -- 외부 인증기관 구분 (실습: MOCK)
    provider_transaction_id VARCHAR(64),             -- 외부 기관 요청 식별값 (composite_key). 등록 응답 유실 시 NULL
    auth_url                VARCHAR(500),            -- 외부 인증 화면 URL (진행 중 재진입용)
    status                  VARCHAR(20)  NOT NULL,   -- PENDING / SUCCEEDED / FAILED / CANCELLED / EXPIRED
    end_reason              VARCHAR(50),             -- 종료 사유 코드 (성공 시 NULL)
    expires_at              TIMESTAMP(6) NOT NULL,   -- 서비스 기준 유효시간
    ended_at                TIMESTAMP(6),            -- 종료 상태 확정 시각
    created_at              TIMESTAMP(6) NOT NULL,
    updated_at              TIMESTAMP(6),
    CONSTRAINT uk_identity_verifications_verification_id UNIQUE (verification_id),
    CONSTRAINT uk_identity_verifications_provider_tx UNIQUE (provider, provider_transaction_id),
    CONSTRAINT fk_identity_verifications_member FOREIGN KEY (member_id) REFERENCES members (id),
    CONSTRAINT identity_verifications_status_check
        CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED'))
);

-- 회원당 진행 중(PENDING) 요청은 최대 1건 — 버튼 연속 클릭·동시 요청에도 DB가 최종 보장
CREATE UNIQUE INDEX uk_identity_verifications_member_pending
    ON identity_verifications (member_id) WHERE status = 'PENDING';

CREATE INDEX idx_identity_verifications_member_created
    ON identity_verifications (member_id, created_at DESC);
