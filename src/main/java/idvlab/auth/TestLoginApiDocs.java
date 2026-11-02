package idvlab.auth;

import idvlab.common.FailureResponse;
import idvlab.common.SuccessResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;

@Tag(name = "실습용 테스트 로그인", description = "소셜 로그인 없이 테스트 회원으로 토큰을 발급합니다.")
public interface TestLoginApiDocs {

    @Operation(summary = "[실습] 테스트 회원 로그인",
            description = """
                    - 허용 키: lab-user-a, lab-user-b, lab-user-c (application.yml)
                    - 받은 accessToken을 Swagger 우측 상단 Authorize에 넣으세요.
                    - 같은 키로 다시 로그인하면 같은 회원입니다 (세션 만료 후 재로그인 재현).
                    - isNewUser=true면 가입 직후 화면에서 선택형 본인확인 안내를 띄웁니다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "로그인 성공",
                    content = @Content(schema = @Schema(implementation = AuthResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "status": 200,
                                      "code": "TEST_LOGIN_SUCCESS",
                                      "message": "[실습] 테스트 회원 로그인에 성공했습니다.",
                                      "data": { "accessToken": "eyJhbGciOiJIUzI1NiJ9...", "name": "실습회원-lab-user-a", "isNewUser": true }
                                    }
                                    """))),
            @ApiResponse(responseCode = "400", description = "허용되지 않은 키 / 키 누락",
                    content = @Content(schema = @Schema(implementation = FailureResponse.class),
                            examples = @ExampleObject(value = """
                                    { "status": 400, "code": "TEST_USER_NOT_ALLOWED", "message": "허용되지 않은 실습용 테스트 회원입니다." }
                                    """)))
    })
    ResponseEntity<SuccessResponse<AuthResponse>> testLogin(@Valid @RequestBody TestLoginRequest request);
}
