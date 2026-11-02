package idvlab.verification.service;

import lombok.RequiredArgsConstructor;
import idvlab.member.Member;
import idvlab.member.MemberRepository;
import idvlab.verification.entity.IdentityProviderType;
import idvlab.verification.entity.IdentityVerification;
import idvlab.verification.entity.VerificationAttemptStatus;
import idvlab.verification.entity.VerificationEndReason;
import idvlab.verification.provider.ProviderOutcome;
import idvlab.verification.provider.ProviderResult;
import idvlab.verification.repository.IdentityVerificationRepository;
import idvlab.common.CommonErrorCode;
import idvlab.common.BusinessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * DB 상태 변경만 담당하는 짧은 트랜잭션 모음. 외부 HTTP 호출은 이 클래스 밖(트랜잭션 밖)에서 한다.
 * 모든 종료 처리는 행 잠금 → "아직 PENDING인지" 재확인 → 반영 순서라서,
 * 성공·취소·만료가 동시에 들어와도 먼저 잠금을 잡은 쪽 하나만 반영된다.
 */
@Component
@RequiredArgsConstructor
public class IdentityVerificationTxService {

    private final IdentityVerificationRepository verificationRepository;
    private final MemberRepository memberRepository;
    private final IdentityVerificationAuditLog auditLog;

    /** 신규 PENDING 시도 저장. 동시 생성은 부분 유니크 인덱스 위반으로 이 트랜잭션만 롤백된다 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IdentityVerification createPending(Long memberId, IdentityProviderType provider,
                                              LocalDateTime now, Duration ttl) {
        return verificationRepository.saveAndFlush(IdentityVerification.start(memberId, provider, now, ttl));
    }

    @Transactional
    public IdentityVerification attachProviderTransaction(Long id, String providerTransactionId, String authUrl) {
        IdentityVerification v = lock(id);
        v.attachProviderTransaction(providerTransactionId, authUrl);
        return v;
    }

    @Transactional
    public IdentityVerification finish(Long id, VerificationAttemptStatus terminal,
                                       VerificationEndReason reason, LocalDateTime now) {
        IdentityVerification v = lock(id);
        if (v.finish(terminal, reason, now)) {
            auditLog.finished(v.getVerificationId(), v.getMemberId(), terminal.name(), String.valueOf(reason), "-");
        }
        return v;
    }

    /**
     * 외부 기관이 확정해 준 결과를 반영한다.
     * - 이미 종료된 시도는 바꾸지 않는다 (상태 역전 금지, 반복 조회 시 중복 반영 없음)
     * - 성공은 서비스 유효시간 안에 완료된 경우에만 인정하고, 그때만 계정을 VERIFIED로 바꾼다
     */
    @Transactional
    public IdentityVerification applyProviderResult(IdentityVerification attempt, ProviderResult result, LocalDateTime now) {
        // 잠금 순서를 탈퇴 처리(회원 → 시도)와 같게 맞춰 교착을 피한다
        Member member = result.outcome() == ProviderOutcome.SUCCEEDED
                ? memberRepository.findByIdForUpdate(attempt.getMemberId())
                    .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND))
                : null;
        IdentityVerification v = lock(attempt.getId());
        if (!v.isPending()) {
            return v;
        }
        switch (result.outcome()) {
            case PENDING -> {
                if (v.isExpiredAt(now)) {
                    v.finish(VerificationAttemptStatus.EXPIRED, VerificationEndReason.EXPIRED_BEFORE_COMPLETION, now);
                }
            }
            case SUCCEEDED -> applySuccess(v, member, result, now);
            case FAILED -> v.finish(VerificationAttemptStatus.FAILED, VerificationEndReason.PROVIDER_REJECTED, now);
            case USER_CANCELLED -> v.finish(VerificationAttemptStatus.CANCELLED, VerificationEndReason.PROVIDER_USER_CANCELLED, now);
            case CLIENT_CANCELLED -> v.finish(VerificationAttemptStatus.CANCELLED, VerificationEndReason.USER_CANCELLED, now);
            case EXPIRED -> v.finish(VerificationAttemptStatus.EXPIRED, VerificationEndReason.PROVIDER_EXPIRED, now);
            case NOT_FOUND -> v.finish(VerificationAttemptStatus.FAILED, VerificationEndReason.PROVIDER_TRANSACTION_NOT_FOUND, now);
        }
        if (!v.isPending()) {
            auditLog.finished(v.getVerificationId(), v.getMemberId(), v.getStatus().name(),
                    String.valueOf(v.getEndReason()), result.providerCode());
        }
        return v;
    }

    private void applySuccess(IdentityVerification v, Member member, ProviderResult result, LocalDateTime now) {
        LocalDateTime completedAt = result.completedAt() != null ? result.completedAt() : now;
        if (completedAt.isAfter(v.getExpiresAt())) {
            v.finish(VerificationAttemptStatus.EXPIRED, VerificationEndReason.SUCCEEDED_AFTER_EXPIRY, now);
            return;
        }
        if (!member.isActive()) {
            // 인증 대기 중 탈퇴: 결과를 계정에 반영하지 않는다
            v.finish(VerificationAttemptStatus.CANCELLED, VerificationEndReason.MEMBER_WITHDRAWN, now);
            return;
        }
        v.finish(VerificationAttemptStatus.SUCCEEDED, null, now);
        if (member.markIdentityVerified(now)) {   // 완료 시각 = 서버가 외부 결과를 확인·반영한 시각
            auditLog.accountVerified(v.getVerificationId(), member.getId());
        }
    }

    private IdentityVerification lock(Long id) {
        return verificationRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR));
    }
}
