package idvlab.verification.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import idvlab.common.BaseTimeEntity;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 본인확인 "시도" 1건.
 * 계정 상태(Member.identityVerificationStatus)와 분리해, 시도가 실패·취소·만료돼도 계정은 미확인으로 남는다.
 */
@Entity
@Table(name = "identity_verifications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdentityVerification extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 36)
    private String verificationId;

    @Column(nullable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IdentityProviderType provider;

    @Column(length = 64)
    private String providerTransactionId;

    @Column(length = 500)
    private String authUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VerificationAttemptStatus status;

    @Enumerated(EnumType.STRING)
    @Column(length = 50)
    private VerificationEndReason endReason;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    private LocalDateTime endedAt;

    public static IdentityVerification start(Long memberId, IdentityProviderType provider,
                                             LocalDateTime now, Duration ttl) {
        IdentityVerification v = new IdentityVerification();
        v.verificationId = UUID.randomUUID().toString();
        v.memberId = memberId;
        v.provider = provider;
        v.status = VerificationAttemptStatus.PENDING;
        v.expiresAt = now.plus(ttl);
        return v;
    }

    /** 외부 기관 등록 결과 연결. 동시 등록 재시도로 같은 값이 다시 와도 결과가 같다 */
    public void attachProviderTransaction(String providerTransactionId, String authUrl) {
        if (!isPending() || this.providerTransactionId != null) {
            return;
        }
        this.providerTransactionId = providerTransactionId;
        this.authUrl = authUrl;
    }

    public boolean isPending() {
        return this.status == VerificationAttemptStatus.PENDING;
    }

    public boolean isRegisteredToProvider() {
        return this.providerTransactionId != null;
    }

    public boolean isExpiredAt(LocalDateTime now) {
        return now.isAfter(this.expiresAt);
    }

    public boolean isOwnedBy(Long memberId) {
        return this.memberId.equals(memberId);
    }

    /**
     * 종료 상태 확정. 이미 종료된 시도는 바꾸지 않고 false를 반환한다 (상태 역전 금지).
     */
    public boolean finish(VerificationAttemptStatus terminal, VerificationEndReason reason, LocalDateTime at) {
        if (!terminal.isTerminal()) {
            throw new IllegalArgumentException("종료 상태만 지정할 수 있습니다: " + terminal);
        }
        if (!isPending()) {
            return false;
        }
        this.status = terminal;
        this.endReason = reason;
        this.endedAt = at;
        return true;
    }
}
