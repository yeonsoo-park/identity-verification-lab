package idvlab.common;

import lombok.Getter;

// 비즈니스 실패는 새 예외 클래스를 만들지 않고 ErrorCode 상수를 추가해 표현한다
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }
}
