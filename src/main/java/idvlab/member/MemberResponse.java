package idvlab.member;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

public record MemberResponse(
        @Schema(description = "이름 (테스트 회원 값)", example = "실습회원-lab-user-a")
        String name,
        @Schema(description = "이메일 (테스트 회원 값)", example = "lab-user-a@lab.invalid")
        String email,
        @Schema(description = "계정 본인확인 상태 (UNVERIFIED: 미확인, VERIFIED: 확인 완료)", example = "VERIFIED")
        IdentityVerificationStatus identityVerificationStatus,
        @Schema(description = "본인확인 완료 시각 (미확인이면 null)", example = "2026-11-02T22:40:12", nullable = true)
        LocalDateTime identityVerifiedAt
) {
    public static MemberResponse from(Member m) {
        return new MemberResponse(m.getName(), m.getEmail(), m.getIdentityVerificationStatus(), m.getIdentityVerifiedAt());
    }
}
