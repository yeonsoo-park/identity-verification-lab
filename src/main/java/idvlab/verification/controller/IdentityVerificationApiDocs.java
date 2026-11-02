package idvlab.verification.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import idvlab.verification.dto.IdentityVerificationResponse;
import idvlab.common.FailureResponse;
import idvlab.common.SuccessResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;

@Tag(name = "본인확인 (실습)", description = """
        가입 후 선택형 본인확인.
        외부 인증기관은 로컬 모의 서버이며 실제 본인확인이 아닙니다.
        회원은 Authorization 토큰으로만 식별하며, 요청 Body/Path로 memberId를 받지 않습니다.
        """)
public interface IdentityVerificationApiDocs {

    String UNAUTHORIZED_EXAMPLE = """
            { "status": 401, "code": "UNAUTHORIZED", "message": "인증이 필요합니다." }
            """;
    String PROVIDER_UNAVAILABLE_EXAMPLE = """
            { "status": 503, "code": "IDENTITY_PROVIDER_UNAVAILABLE", "message": "인증기관 응답을 확인하지 못했습니다. 잠시 후 다시 확인해주세요." }
            """;
    String NOT_FOUND_EXAMPLE = """
            { "status": 404, "code": "IDENTITY_VERIFICATION_NOT_FOUND", "message": "본인확인 요청을 찾을 수 없습니다." }
            """;

    @Operation(summary = "본인확인 요청 생성",
            description = """
                    마이페이지 '본인확인하기' 또는 가입 완료 화면의 선택형 진입점에서 호출합니다.
                    - 응답의 authUrl로 외부(모의) 인증 화면을 엽니다.
                    - 진행 중(PENDING) 요청이 이미 있으면 새로 만들지 않고 그 요청을 200(RESUMED)으로 돌려줍니다.
                      → 버튼 연속 클릭·화면 재진입·세션 만료 후 재로그인에도 요청이 중복 생성되지 않습니다.
                    - 이전 요청이 외부에서 이미 성공했다면 이번 호출에서 계정에 반영되고 409(이미 확인 완료)를 응답합니다.
                    - 외부 기관 연결 실패(503) 시 요청은 PENDING으로 남으며, 같은 API를 다시 호출하면 같은 요청으로 재등록합니다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "새 요청 생성",
                    content = @Content(schema = @Schema(implementation = IdentityVerificationResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "status": 201,
                                      "code": "IDENTITY_VERIFICATION_CREATED",
                                      "message": "본인확인 요청을 생성했습니다.",
                                      "data": {
                                        "verificationId": "8f6c3a6e-2a0c-4f43-9d0e-6b3c1f0e2d11",
                                        "status": "PENDING",
                                        "endReason": null,
                                        "authUrl": "http://localhost:18090/mock-idp/web/standard-auth?trx_key=3f1d0c9a7b",
                                        "expiresAt": "2026-11-02T22:45:00",
                                        "endedAt": null,
                                        "pollIntervalSeconds": 2,
                                        "accountStatus": "UNVERIFIED",
                                        "identityVerifiedAt": null
                                      }
                                    }
                                    """))),
            @ApiResponse(responseCode = "200", description = "진행 중 요청 재사용 (중복 생성 없음)",
                    content = @Content(schema = @Schema(implementation = IdentityVerificationResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "status": 200,
                                      "code": "IDENTITY_VERIFICATION_RESUMED",
                                      "message": "진행 중인 본인확인 요청을 이어서 진행합니다.",
                                      "data": { "verificationId": "8f6c3a6e-2a0c-4f43-9d0e-6b3c1f0e2d11", "status": "PENDING", "...": "..." }
                                    }
                                    """))),
            @ApiResponse(responseCode = "401", description = "로그인 필요",
                    content = @Content(schema = @Schema(implementation = FailureResponse.class),
                            examples = @ExampleObject(value = UNAUTHORIZED_EXAMPLE))),
            @ApiResponse(responseCode = "409", description = "이미 본인확인 완료된 계정",
                    content = @Content(schema = @Schema(implementation = FailureResponse.class),
                            examples = @ExampleObject(value = """
                                    { "status": 409, "code": "IDENTITY_ALREADY_VERIFIED", "message": "이미 본인확인이 완료된 계정입니다." }
                                    """))),
            @ApiResponse(responseCode = "503", description = "외부(모의) 기관 연결 실패·시간 초과 — 같은 API 재호출로 이어서 진행",
                    content = @Content(schema = @Schema(implementation = FailureResponse.class),
                            examples = @ExampleObject(value = PROVIDER_UNAVAILABLE_EXAMPLE)))
    })
    ResponseEntity<SuccessResponse<IdentityVerificationResponse>> start(
            @Parameter(hidden = true) @AuthenticationPrincipal Long memberId);

    @Operation(summary = "본인확인 진행 상태 조회 (외부 결과 확인 포함)",
            description = """
                    외부 인증 화면에서 돌아왔을 때, 또는 pollIntervalSeconds 간격으로 호출합니다.
                    - PENDING이면 서버가 외부(모의) 기관에 결과를 직접 조회해 반영합니다. 클라이언트가 보낸 성공 값은 사용하지 않습니다.
                    - 외부 결과는 전자서명(SHA256withRSA, 실습용 Lab Root CA 체계)을 검증하고, 이 요청(verificationId)의 결과인지 대조한 뒤에만 반영합니다.
                    - 이미 종료된 요청은 외부를 다시 조회하지 않고 저장된 상태를 그대로 응답합니다. (반복 조회해도 중복 반영 없음)
                    - 503은 '실패'가 아닙니다. 상태는 PENDING으로 유지되며 잠시 후 재조회하면 복구됩니다.
                    - 다른 회원의 verificationId는 존재 여부를 알 수 없도록 404로 응답합니다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공 (status로 화면 분기)",
                    content = @Content(schema = @Schema(implementation = IdentityVerificationResponse.class),
                            examples = {
                                    @ExampleObject(name = "성공 반영됨", value = """
                                            {
                                              "status": 200,
                                              "code": "IDENTITY_VERIFICATION_FETCHED",
                                              "message": "본인확인 진행 상태 조회에 성공했습니다.",
                                              "data": {
                                                "verificationId": "8f6c3a6e-2a0c-4f43-9d0e-6b3c1f0e2d11",
                                                "status": "SUCCEEDED",
                                                "endReason": null,
                                                "authUrl": null,
                                                "expiresAt": "2026-11-02T22:45:00",
                                                "endedAt": "2026-11-02T22:41:03",
                                                "pollIntervalSeconds": 2,
                                                "accountStatus": "VERIFIED",
                                                "identityVerifiedAt": "2026-11-02T22:41:03"
                                              }
                                            }
                                            """),
                                    @ExampleObject(name = "외부 화면에서 취소", value = """
                                            {
                                              "status": 200,
                                              "code": "IDENTITY_VERIFICATION_FETCHED",
                                              "message": "본인확인 진행 상태 조회에 성공했습니다.",
                                              "data": {
                                                "verificationId": "8f6c3a6e-2a0c-4f43-9d0e-6b3c1f0e2d11",
                                                "status": "CANCELLED",
                                                "endReason": "PROVIDER_USER_CANCELLED",
                                                "authUrl": null,
                                                "expiresAt": "2026-11-02T22:45:00",
                                                "endedAt": "2026-11-02T22:41:03",
                                                "pollIntervalSeconds": 2,
                                                "accountStatus": "UNVERIFIED",
                                                "identityVerifiedAt": null
                                              }
                                            }
                                            """)
                            })),
            @ApiResponse(responseCode = "401", description = "로그인 필요",
                    content = @Content(schema = @Schema(implementation = FailureResponse.class),
                            examples = @ExampleObject(value = UNAUTHORIZED_EXAMPLE))),
            @ApiResponse(responseCode = "404", description = "없는 요청 또는 다른 회원의 요청",
                    content = @Content(schema = @Schema(implementation = FailureResponse.class),
                            examples = @ExampleObject(value = NOT_FOUND_EXAMPLE))),
            @ApiResponse(responseCode = "502", description = "외부 결과의 전자서명 검증 실패 (위·변조 또는 신뢰할 수 없는 서명자). 상태 변경 없음, 재조회 필요",
                    content = @Content(schema = @Schema(implementation = FailureResponse.class),
                            examples = @ExampleObject(value = """
                                    { "status": 502, "code": "IDENTITY_RESULT_INTEGRITY_FAILED", "message": "인증기관 응답의 전자서명을 확인하지 못했습니다. 잠시 후 다시 확인해주세요." }
                                    """))),
            @ApiResponse(responseCode = "503", description = "외부 결과 확인 불가 (상태 변경 없음, 재조회 필요)",
                    content = @Content(schema = @Schema(implementation = FailureResponse.class),
                            examples = @ExampleObject(value = PROVIDER_UNAVAILABLE_EXAMPLE)))
    })
    ResponseEntity<SuccessResponse<IdentityVerificationResponse>> get(
            @Parameter(hidden = true) @AuthenticationPrincipal Long memberId,
            @Parameter(description = "요청 생성 시 받은 verificationId", example = "8f6c3a6e-2a0c-4f43-9d0e-6b3c1f0e2d11")
            @PathVariable String verificationId);

    @Operation(summary = "본인확인 요청 취소",
            description = """
                    사용자가 서비스 화면에서 '취소'를 명시적으로 눌렀을 때만 호출합니다. (화면 닫기는 취소가 아님 → 호출하지 않음)
                    - 응답은 항상 '처리 후 최종 상태'입니다. 취소보다 외부 성공이 먼저 확정됐다면 SUCCEEDED가 올 수 있습니다.
                    - 이미 종료된 요청에 다시 호출해도 상태를 바꾸지 않고 현재 상태를 200으로 응답합니다. (멱등)
                    - 외부 기관 연결이 안 되면 서비스 기준으로 CANCELLED(USER_CANCELLED_PROVIDER_UNCONFIRMED) 확정합니다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "취소 처리 (최종 상태 응답)",
                    content = @Content(schema = @Schema(implementation = IdentityVerificationResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "status": 200,
                                      "code": "IDENTITY_VERIFICATION_CANCEL_PROCESSED",
                                      "message": "본인확인 요청 취소를 처리했습니다.",
                                      "data": {
                                        "verificationId": "8f6c3a6e-2a0c-4f43-9d0e-6b3c1f0e2d11",
                                        "status": "CANCELLED",
                                        "endReason": "USER_CANCELLED",
                                        "authUrl": null,
                                        "expiresAt": "2026-11-02T22:45:00",
                                        "endedAt": "2026-11-02T22:40:30",
                                        "pollIntervalSeconds": 2,
                                        "accountStatus": "UNVERIFIED",
                                        "identityVerifiedAt": null
                                      }
                                    }
                                    """))),
            @ApiResponse(responseCode = "401", description = "로그인 필요",
                    content = @Content(schema = @Schema(implementation = FailureResponse.class),
                            examples = @ExampleObject(value = UNAUTHORIZED_EXAMPLE))),
            @ApiResponse(responseCode = "404", description = "없는 요청 또는 다른 회원의 요청",
                    content = @Content(schema = @Schema(implementation = FailureResponse.class),
                            examples = @ExampleObject(value = NOT_FOUND_EXAMPLE)))
    })
    ResponseEntity<SuccessResponse<IdentityVerificationResponse>> cancel(
            @Parameter(hidden = true) @AuthenticationPrincipal Long memberId,
            @Parameter(description = "요청 생성 시 받은 verificationId", example = "8f6c3a6e-2a0c-4f43-9d0e-6b3c1f0e2d11")
            @PathVariable String verificationId);
}
