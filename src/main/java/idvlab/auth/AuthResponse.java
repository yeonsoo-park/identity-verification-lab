package idvlab.auth;

import io.swagger.v3.oas.annotations.media.Schema;

public record AuthResponse(
        @Schema(description = "액세스 토큰 (Authorization: Bearer)") String accessToken,
        @Schema(description = "이름 (테스트 회원 값)", example = "실습회원-lab-user-a") String name,
        @Schema(description = "이번 로그인으로 새로 가입했는지 — true면 가입 직후 선택형 본인확인 안내를 띄운다", example = "true")
        boolean isNewUser
) {
}
