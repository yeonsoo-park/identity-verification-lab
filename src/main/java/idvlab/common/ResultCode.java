package idvlab.common;

import org.springframework.http.HttpStatus;

// 모든 성공·오류 코드의 공통 형태: HTTP 상태 + 안정적인 코드명(name) + 사람이 읽는 메시지
public interface ResultCode {
    HttpStatus getStatus();
    String getMessage();
    String name();
}
