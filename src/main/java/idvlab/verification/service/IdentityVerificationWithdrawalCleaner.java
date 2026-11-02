package idvlab.verification.service;

import lombok.RequiredArgsConstructor;
import idvlab.member.WithdrawalCleaner;
import idvlab.verification.repository.IdentityVerificationRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

// 인증 대기 중 탈퇴 → 진행 중 요청을 취소로 확정해, 이후 외부 성공이 와도 반영되지 않게 한다
@Component
@RequiredArgsConstructor
public class IdentityVerificationWithdrawalCleaner implements WithdrawalCleaner {

    private final IdentityVerificationRepository identityVerificationRepository;
    private final IdentityVerificationAuditLog auditLog;

    @Override
    public void clean(Long memberId) {
        int cancelled = identityVerificationRepository.cancelPendingByWithdrawal(memberId, LocalDateTime.now());
        if (cancelled > 0) {
            auditLog.withdrawalCancelled(memberId, cancelled);
        }
    }
}
