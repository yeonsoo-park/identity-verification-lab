package idvlab.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<FailureResponse> handleBusiness(BusinessException e) {
        return respond(e.getErrorCode(), e.getErrorCode().getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<FailureResponse> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst().map(f -> f.getField() + ": " + f.getDefaultMessage())
                .orElse(CommonErrorCode.INVALID_INPUT_VALUE.getMessage());
        return respond(CommonErrorCode.INVALID_INPUT_VALUE, message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<FailureResponse> handleNotReadable(HttpMessageNotReadableException e) {
        return respond(CommonErrorCode.MESSAGE_NOT_READABLE, CommonErrorCode.MESSAGE_NOT_READABLE.getMessage());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<FailureResponse> handleNoResource(NoResourceFoundException e) {
        return respond(CommonErrorCode.INVALID_ENDPOINT, CommonErrorCode.INVALID_ENDPOINT.getMessage());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<FailureResponse> handleMethod(HttpRequestMethodNotSupportedException e) {
        return respond(CommonErrorCode.METHOD_NOT_ALLOWED, CommonErrorCode.METHOD_NOT_ALLOWED.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<FailureResponse> handleUnknown(Exception e) throws Exception {
        if (e.getClass().getName().startsWith("org.springframework.security")) {
            throw e;   // 401/403은 Spring Security 핸들러가 응답하도록 넘긴다
        }
        log.error("Unhandled exception", e);
        return respond(CommonErrorCode.INTERNAL_SERVER_ERROR, CommonErrorCode.INTERNAL_SERVER_ERROR.getMessage());
    }

    private static ResponseEntity<FailureResponse> respond(ErrorCode code, String message) {
        return ResponseEntity.status(code.getStatus()).body(FailureResponse.of(code, message));
    }
}
