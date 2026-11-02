package idvlab.verification;

import idvlab.common.SuccessCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

// 선택형 본인확인 API 결과 코드
@Getter
@RequiredArgsConstructor
public enum IdentityVerificationSuccessCode implements SuccessCode {

    IDENTITY_VERIFICATION_CREATED(HttpStatus.CREATED, "본인확인 요청을 생성했습니다."),
    IDENTITY_VERIFICATION_RESUMED(HttpStatus.OK, "진행 중인 본인확인 요청을 이어서 진행합니다."),
    IDENTITY_VERIFICATION_FETCHED(HttpStatus.OK, "본인확인 진행 상태 조회에 성공했습니다."),
    IDENTITY_VERIFICATION_CANCEL_PROCESSED(HttpStatus.OK, "본인확인 요청 취소를 처리했습니다."),
    ;

    private final HttpStatus status;
    private final String message;
}
