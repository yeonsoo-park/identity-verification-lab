package idvlab.verification.provider;

import idvlab.verification.entity.IdentityProviderType;

/**
 * 외부 본인확인기관 연동 인터페이스. 서비스 도메인은 이 인터페이스만 알고, 기관별 HTTP 규격은 구현체가 감춘다.
 * 실습에서는 로컬 모의 기관 구현체(MockIdentityProviderClient)만 존재한다.
 *
 * 네트워크 오류·타임아웃·5xx는 BusinessException(IDENTITY_PROVIDER_UNAVAILABLE)으로 던진다.
 * → 호출 측은 이를 "인증 실패"로 단정하지 않는다.
 */
public interface IdentityVerificationProvider {

    IdentityProviderType type();

    /** 인증 요청 등록. clientRequestId 기준으로 멱등이어야 한다 (응답 유실 후 재시도 시 같은 요청 반환) */
    ProviderTransaction register(String clientRequestId, String callbackUrl);

    /**
     * 결과 조회 (polling). clientRequestId는 응답이 "이 요청"의 결과인지 대조하는 데 쓴다
     * (다른 요청의 결과를 끼워 넣는 응답 바꿔치기 방지).
     * 응답 무결성 검증 실패는 BusinessException(IDENTITY_RESULT_INTEGRITY_FAILED)으로 던진다.
     */
    ProviderResult fetchResult(String providerTransactionId, String clientRequestId);

    /** 이용기관 요청 취소. 이미 종료된 요청이면 그 종료 상태를 그대로 돌려준다 */
    ProviderResult cancel(String providerTransactionId, String clientRequestId);
}
