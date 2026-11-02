package idvlab.verification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import idvlab.member.IdentityVerificationStatus;
import idvlab.member.Member;
import idvlab.verification.entity.IdentityProviderType;
import idvlab.verification.entity.IdentityVerification;
import idvlab.verification.entity.VerificationAttemptStatus;
import idvlab.verification.entity.VerificationEndReason;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 상태 규칙 단위 테스트 (DB·Spring 없이 실행)
class IdentityVerificationStateTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 2, 22, 0);

    private IdentityVerification pending() {
        return IdentityVerification.start(1L, IdentityProviderType.MOCK, NOW, Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("새 시도는 PENDING이고 유효시간은 생성 시각 + TTL")
    void start() {
        IdentityVerification v = pending();
        assertThat(v.getStatus()).isEqualTo(VerificationAttemptStatus.PENDING);
        assertThat(v.getExpiresAt()).isEqualTo(NOW.plusMinutes(5));
        assertThat(v.isExpiredAt(NOW.plusMinutes(5))).isFalse();
        assertThat(v.isExpiredAt(NOW.plusMinutes(5).plusSeconds(1))).isTrue();
    }

    @Test
    @DisplayName("종료된 시도는 다른 종료 상태로 바뀌지 않는다 (상태 역전 금지)")
    void noReversal() {
        IdentityVerification v = pending();
        assertThat(v.finish(VerificationAttemptStatus.EXPIRED, VerificationEndReason.EXPIRED_BEFORE_COMPLETION, NOW)).isTrue();
        assertThat(v.finish(VerificationAttemptStatus.SUCCEEDED, null, NOW.plusSeconds(1))).isFalse();
        assertThat(v.finish(VerificationAttemptStatus.CANCELLED, VerificationEndReason.USER_CANCELLED, NOW)).isFalse();
        assertThat(v.getStatus()).isEqualTo(VerificationAttemptStatus.EXPIRED);
        assertThat(v.getEndedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("PENDING으로 되돌리는 종료 처리는 허용하지 않는다")
    void cannotFinishAsPending() {
        assertThatThrownBy(() -> pending().finish(VerificationAttemptStatus.PENDING, null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("외부 등록 정보는 한 번만 연결된다 (재등록 응답이 와도 그대로)")
    void attachOnce() {
        IdentityVerification v = pending();
        v.attachProviderTransaction("tx-1", "http://localhost/a");
        v.attachProviderTransaction("tx-2", "http://localhost/b");
        assertThat(v.getProviderTransactionId()).isEqualTo("tx-1");
        assertThat(v.getAuthUrl()).isEqualTo("http://localhost/a");
    }

    @Test
    @DisplayName("계정 본인확인 반영은 1회만 — 반복 반영해도 완료 시각 불변")
    void markVerifiedOnce() {
        Member user = Member.join("p", "n", "e@lab.invalid");
        assertThat(user.getIdentityVerificationStatus()).isEqualTo(IdentityVerificationStatus.UNVERIFIED);
        assertThat(user.markIdentityVerified(NOW)).isTrue();
        assertThat(user.markIdentityVerified(NOW.plusHours(1))).isFalse();
        assertThat(user.getIdentityVerifiedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("탈퇴 시 계정 본인확인 상태도 개인정보와 함께 초기화")
    void withdrawalResetsVerification() {
        Member user = Member.join("p", "n", "e@lab.invalid");
        user.markIdentityVerified(NOW);
        user.withdraw(NOW);
        assertThat(user.getIdentityVerificationStatus()).isEqualTo(IdentityVerificationStatus.UNVERIFIED);
        assertThat(user.getIdentityVerifiedAt()).isNull();
    }
}
