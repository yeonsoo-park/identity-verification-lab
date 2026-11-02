package idvlab.verification.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import idvlab.member.IdentityVerificationStatus;
import idvlab.member.Member;
import idvlab.verification.entity.IdentityVerification;
import idvlab.verification.entity.VerificationAttemptStatus;
import idvlab.verification.entity.VerificationEndReason;

import java.time.LocalDateTime;

public record IdentityVerificationResponse(
        @Schema(description = "서비스 내부 본인확인 요청 식별값 (조회·취소 시 Path에 사용)", example = "8f6c3a6e-2a0c-4f43-9d0e-6b3c1f0e2d11")
        String verificationId,

        @Schema(description = "인증 시도 상태", example = "PENDING",
                allowableValues = {"PENDING", "SUCCEEDED", "FAILED", "CANCELLED", "EXPIRED"})
        VerificationAttemptStatus status,

        @Schema(description = "종료 사유 코드 (PENDING·SUCCEEDED면 null)", example = "PROVIDER_USER_CANCELLED", nullable = true)
        VerificationEndReason endReason,

        @Schema(description = "외부(모의) 인증 화면 URL. PENDING이고 외부 등록이 끝났을 때만 값이 있음", nullable = true,
                example = "http://localhost:18090/mock-idp/web/standard-auth?trx_key=3f1d...")
        String authUrl,

        @Schema(description = "이 시도의 유효시간 (서비스 기준)", example = "2026-11-02T22:45:00")
        LocalDateTime expiresAt,

        @Schema(description = "종료 상태 확정 시각 (PENDING이면 null)", nullable = true, example = "2026-11-02T22:41:03")
        LocalDateTime endedAt,

        @Schema(description = "결과 조회 권장 간격(초). 실습용 가정값", example = "2")
        long pollIntervalSeconds,

        @Schema(description = "응답 시점의 계정 본인확인 상태", example = "UNVERIFIED")
        IdentityVerificationStatus accountStatus,

        @Schema(description = "계정 본인확인 완료 시각 (미확인이면 null)", nullable = true, example = "2026-11-02T22:41:03")
        LocalDateTime identityVerifiedAt
) {
    public static IdentityVerificationResponse of(IdentityVerification v, Member member, long pollIntervalSeconds) {
        return new IdentityVerificationResponse(
                v.getVerificationId(),
                v.getStatus(),
                v.getEndReason(),
                v.isPending() ? v.getAuthUrl() : null,
                v.getExpiresAt(),
                v.getEndedAt(),
                pollIntervalSeconds,
                member.getIdentityVerificationStatus(),
                member.getIdentityVerifiedAt()
        );
    }
}
