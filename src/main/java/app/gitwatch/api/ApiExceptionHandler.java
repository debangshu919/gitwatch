package app.gitwatch.api;

import app.gitwatch.api.ApiEnvelopes.ErrorBody;
import app.gitwatch.api.ApiEnvelopes.ErrorResponse;
import app.gitwatch.service.UnsupportedChannelException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> api(ApiException ex) {
        return ResponseEntity.status(ex.status()).body(error(ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(UnsupportedChannelException.class)
    public ResponseEntity<ErrorResponse> unsupportedChannel(UnsupportedChannelException ex) {
        return ResponseEntity.badRequest().body(error(ApiCodes.UNSUPPORTED_CHANNEL, "telegram channels are not supported"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> unreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(error(ApiCodes.VALIDATION_ERROR, "Request body is not valid"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> badPath(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest().body(error(ApiCodes.VALIDATION_ERROR, "Invalid id"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> conflict(DataIntegrityViolationException ex) {
        return ResponseEntity.status(409).body(error(ApiCodes.CONFLICT, "That record already exists"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> unexpected(Exception ex) {
        log.error("request failed", ex);
        return ResponseEntity.internalServerError().body(error(ApiCodes.INTERNAL_ERROR, "Something went wrong"));
    }

    private static ErrorResponse error(String code, String message) {
        return new ErrorResponse(new ErrorBody(code, message));
    }
}
