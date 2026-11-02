package idvlab.verification;

import idvlab.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

// 선택형 본인확인 API 결과 코드
@Getter
@RequiredArgsConstructor
public enum IdentityVerificationErrorCode implements ErrorCode {

    IDENTITY_VERIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "본인확인 요청을 찾을 수 없습니다."),
    IDENTITY_ALREADY_VERIFIED(HttpStatus.CONFLICT, "이미 본인확인이 완료된 계정입니다."),
    IDENTITY_PROVIDER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "인증기관 응답을 확인하지 못했습니다. 잠시 후 다시 확인해주세요."),
    IDENTITY_RESULT_INTEGRITY_FAILED(HttpStatus.BAD_GATEWAY, "인증기관 응답의 전자서명을 확인하지 못했습니다. 잠시 후 다시 확인해주세요."),
    ;

    private final HttpStatus status;
    private final String message;
}
