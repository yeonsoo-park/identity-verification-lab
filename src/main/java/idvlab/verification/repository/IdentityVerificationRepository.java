package idvlab.verification.repository;

import jakarta.persistence.LockModeType;
import idvlab.verification.entity.IdentityVerification;
import idvlab.verification.entity.VerificationAttemptStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface IdentityVerificationRepository extends JpaRepository<IdentityVerification, Long> {

    // 회원 식별은 항상 로그인 토큰의 memberId로 — 다른 회원의 요청은 "없는 요청"과 똑같이 취급
    Optional<IdentityVerification> findByVerificationIdAndMemberId(String verificationId, Long memberId);

    Optional<IdentityVerification> findByMemberIdAndStatus(Long memberId, VerificationAttemptStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from IdentityVerification v where v.id = :id")
    Optional<IdentityVerification> findByIdForUpdate(@Param("id") Long id);

    // clearAutomatically를 쓰지 않는다: 탈퇴 트랜잭션에서 잠가 둔 Member 엔티티가 분리돼 탈퇴 변경이 유실된다
    @Modifying(flushAutomatically = true)
    @Query("""
    update IdentityVerification v
       set v.status = idvlab.verification.entity.VerificationAttemptStatus.CANCELLED,
           v.endReason = idvlab.verification.entity.VerificationEndReason.MEMBER_WITHDRAWN,
           v.endedAt = :now
     where v.memberId = :memberId
       and v.status = idvlab.verification.entity.VerificationAttemptStatus.PENDING
    """)
    int cancelPendingByWithdrawal(@Param("memberId") Long memberId, @Param("now") LocalDateTime now);
}
