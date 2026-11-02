package idvlab.auth;

import idvlab.common.ErrorCode;
import idvlab.common.SuccessCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

public final class AuthCode {

    private AuthCode() {}

    @Getter
    @RequiredArgsConstructor
    public enum Success implements SuccessCode {
        TEST_LOGIN_SUCCESS(HttpStatus.OK, "[실습] 테스트 회원 로그인에 성공했습니다."),
        ;
        private final HttpStatus status;
        private final String message;
    }

    @Getter
    @RequiredArgsConstructor
    public enum Error implements ErrorCode {
        TEST_USER_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "허용되지 않은 실습용 테스트 회원입니다."),
        ;
        private final HttpStatus status;
        private final String message;
    }
}
