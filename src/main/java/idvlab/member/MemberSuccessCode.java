package idvlab.member;

import idvlab.common.SuccessCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum MemberSuccessCode implements SuccessCode {

    MEMBER_INFO_FETCHED(HttpStatus.OK, "회원 정보 조회에 성공했습니다."),
    ;

    private final HttpStatus status;
    private final String message;
}
