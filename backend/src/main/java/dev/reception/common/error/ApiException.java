package dev.reception.common.error;

import java.util.List;

/**
 * The one exception type application code throws to produce a non-2xx response.
 *
 * <p>Carrying an {@link ErrorCode} rather than a status means the handler never has to infer one,
 * and every response body is produced in exactly one place.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final transient List<FieldError> fieldErrors;

    public ApiException(ErrorCode code, String detail) {
        this(code, detail, List.of());
    }

    public ApiException(ErrorCode code, String detail, List<FieldError> fieldErrors) {
        super(detail);
        this.code = code;
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    public static ApiException notFound(String detail) {
        return new ApiException(ErrorCode.NOT_FOUND, detail);
    }

    public static ApiException forbidden(String detail) {
        return new ApiException(ErrorCode.FORBIDDEN, detail);
    }

    public ErrorCode code() {
        return code;
    }

    public List<FieldError> fieldErrors() {
        return fieldErrors;
    }
}
