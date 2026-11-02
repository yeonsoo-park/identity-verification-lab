package idvlab.verification.entity;

// 인증 시도가 종료된 사유. 성공(SUCCEEDED)은 사유 없음(null)
public enum VerificationEndReason {
    // 사용자·회원 행동
    USER_CANCELLED,                          // 서비스 화면에서 명시적 취소 + 외부 기관도 취소 확인
    USER_CANCELLED_PROVIDER_UNCONFIRMED,     // 서비스 화면에서 취소, 외부 기관 연결 실패로 기관 측 취소는 미확인
    MEMBER_WITHDRAWN,                        // 인증 대기 중 회원 탈퇴

    // 외부 기관이 의미를 확정한 결과
    PROVIDER_USER_CANCELLED,                 // 외부 인증 화면에서 사용자가 취소
    PROVIDER_REJECTED,                       // 외부 기관이 인증 실패로 판정
    PROVIDER_EXPIRED,                        // 외부 기관 유효시간 경과
    PROVIDER_TRANSACTION_NOT_FOUND,          // 외부 기관에 해당 요청이 존재하지 않음

    // 서비스 유효시간 정책
    EXPIRED_BEFORE_COMPLETION,               // 서비스 유효시간까지 외부 기관에서 완료되지 않음
    SUCCEEDED_AFTER_EXPIRY,                  // 외부 성공 시각이 서비스 유효시간 이후 → 인정하지 않음
    UNCONFIRMED_AFTER_EXPIRY,                // 유효시간 + 유예시간이 지나도록 외부 결과를 확인할 수 없음
    REGISTRATION_UNCONFIRMED                 // 외부 기관 등록 응답을 받지 못한 채 유효시간 경과
}
