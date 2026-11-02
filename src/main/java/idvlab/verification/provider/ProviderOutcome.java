package idvlab.verification.provider;

// 외부 기관이 "의미를 확정해" 알려준 결과. 네트워크 오류는 여기에 포함되지 않는다(예외로 구분)
public enum ProviderOutcome {
    PENDING,
    SUCCEEDED,
    FAILED,
    USER_CANCELLED,     // 외부 인증 화면에서 사용자가 취소
    CLIENT_CANCELLED,   // 이용기관의 취소 요청으로 취소
    EXPIRED,
    NOT_FOUND
}
