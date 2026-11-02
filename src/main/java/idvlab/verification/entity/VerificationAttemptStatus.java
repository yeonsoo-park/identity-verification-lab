package idvlab.verification.entity;

// 개별 인증 시도 상태. PENDING만 진행 중이고 나머지는 종료 상태(다시 바뀌지 않음)
public enum VerificationAttemptStatus {
    PENDING, SUCCEEDED, FAILED, CANCELLED, EXPIRED;

    public boolean isTerminal() {
        return this != PENDING;
    }
}
