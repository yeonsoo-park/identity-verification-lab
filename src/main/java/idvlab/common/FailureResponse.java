package idvlab.common;

// 공통 실패 응답 봉투: { status, code, message }
public record FailureResponse(int status, String code, String message) {
    public static FailureResponse of(ErrorCode code) {
        return new FailureResponse(code.getStatus().value(), code.name(), code.getMessage());
    }

    public static FailureResponse of(ErrorCode code, String message) {
        return new FailureResponse(code.getStatus().value(), code.name(), message);
    }
}
