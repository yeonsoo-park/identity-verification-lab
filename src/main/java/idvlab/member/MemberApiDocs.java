package idvlab.member;

import idvlab.common.FailureResponse;
import idvlab.common.SuccessResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

@Tag(name = "회원", description = "마이페이지 회원 정보·탈퇴")
public interface MemberApiDocs {

    @Operation(summary = "내 정보 조회",
            description = """
                    마이페이지 본인확인 영역을 그립니다.
                    - UNVERIFIED → '본인확인하기' 버튼, VERIFIED → '본인확인 완료' + 완료 시각
                    - 계정 상태만 읽으며 진행 중인 인증 시도의 외부 결과를 확인하지 않습니다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(schema = @Schema(implementation = MemberResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "status": 200,
                                      "code": "MEMBER_INFO_FETCHED",
                                      "message": "회원 정보 조회에 성공했습니다.",
                                      "data": {
                                        "name": "실습회원-lab-user-a",
                                        "email": "lab-user-a@lab.invalid",
                                        "identityVerificationStatus": "VERIFIED",
                                        "identityVerifiedAt": "2026-11-02T22:40:12"
                                      }
                                    }
                                    """))),
            @ApiResponse(responseCode = "401", description = "인증 필요 (토큰 없음·만료·탈퇴 회원)",
                    content = @Content(schema = @Schema(implementation = FailureResponse.class),
                            examples = @ExampleObject(value = """
                                    { "status": 401, "code": "UNAUTHORIZED", "message": "인증이 필요합니다." }
                                    """)))
    })
    ResponseEntity<SuccessResponse<MemberResponse>> getMyInfo(@Parameter(hidden = true) @AuthenticationPrincipal Long memberId);

    @Operation(summary = "회원 탈퇴",
            description = """
                    즉시 탈퇴합니다. 진행 중인 본인확인 요청은 취소로 확정되고, 이후 외부에서 성공이 와도 반영되지 않습니다.
                    탈퇴 후 같은 토큰은 401이며, 같은 테스트 키로 다시 로그인하면 새 계정(미확인)으로 가입됩니다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "탈퇴 완료"),
            @ApiResponse(responseCode = "401", description = "인증 필요",
                    content = @Content(schema = @Schema(implementation = FailureResponse.class)))
    })
    ResponseEntity<Void> withdraw(@Parameter(hidden = true) @AuthenticationPrincipal Long memberId);
}
