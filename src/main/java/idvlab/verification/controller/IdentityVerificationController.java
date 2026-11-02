package idvlab.verification.controller;

import lombok.RequiredArgsConstructor;
import idvlab.verification.dto.IdentityVerificationResponse;
import idvlab.verification.service.IdentityVerificationService;
import idvlab.verification.IdentityVerificationSuccessCode;
import idvlab.common.SuccessResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/members/me/identity-verifications")
@RequiredArgsConstructor
public class IdentityVerificationController implements IdentityVerificationApiDocs {

    private final IdentityVerificationService identityVerificationService;

    @PostMapping
    public ResponseEntity<SuccessResponse<IdentityVerificationResponse>> start(@AuthenticationPrincipal Long memberId) {
        IdentityVerificationService.StartResult result = identityVerificationService.start(memberId);
        if (result.created()) {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(SuccessResponse.of(IdentityVerificationSuccessCode.IDENTITY_VERIFICATION_CREATED, result.response()));
        }
        return ResponseEntity.ok(SuccessResponse.of(IdentityVerificationSuccessCode.IDENTITY_VERIFICATION_RESUMED, result.response()));
    }

    @GetMapping("/{verificationId}")
    public ResponseEntity<SuccessResponse<IdentityVerificationResponse>> get(
            @AuthenticationPrincipal Long memberId,
            @PathVariable String verificationId) {
        return ResponseEntity.ok(SuccessResponse.of(IdentityVerificationSuccessCode.IDENTITY_VERIFICATION_FETCHED,
                identityVerificationService.get(memberId, verificationId)));
    }

    @PostMapping("/{verificationId}/cancel")
    public ResponseEntity<SuccessResponse<IdentityVerificationResponse>> cancel(
            @AuthenticationPrincipal Long memberId,
            @PathVariable String verificationId) {
        return ResponseEntity.ok(SuccessResponse.of(IdentityVerificationSuccessCode.IDENTITY_VERIFICATION_CANCEL_PROCESSED,
                identityVerificationService.cancel(memberId, verificationId)));
    }
}
