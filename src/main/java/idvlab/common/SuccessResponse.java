package idvlab.common;

// 공통 성공 응답 봉투: { status, code, message, data }
public record SuccessResponse<T>(int status, String code, String message, T data) {
    public static <T> SuccessResponse<T> of(SuccessCode code, T data) {
        return new SuccessResponse<>(code.getStatus().value(), code.name(), code.getMessage(), data);
    }
}
