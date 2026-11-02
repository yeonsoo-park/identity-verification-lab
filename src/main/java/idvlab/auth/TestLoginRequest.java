package idvlab.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record TestLoginRequest(
        @Schema(description = "테스트 회원 키 (lab.test-users 중 하나)", example = "lab-user-a",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank String testUserKey
) {
}
