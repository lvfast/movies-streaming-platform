package com.lvfast.streaming.common;

import com.lvfast.streaming.administration.AdminValidationException;
import com.lvfast.streaming.administration.IfMatchRequiredException;
import com.lvfast.streaming.administration.PublicationRejectedException;
import com.lvfast.streaming.administration.SlugUnavailableException;
import com.lvfast.streaming.administration.StaleRevisionException;
import com.lvfast.streaming.catalog.CatalogValidationException;
import com.lvfast.streaming.catalog.MovieNotFoundException;
import com.lvfast.streaming.identity.IdentityValidationException;
import com.lvfast.streaming.identity.InvalidCredentialsException;
import com.lvfast.streaming.identity.InvalidRefreshTokenException;
import com.lvfast.streaming.identity.RateLimitExceededException;
import com.lvfast.streaming.identity.RefreshTokenReuseException;
import com.lvfast.streaming.identity.UsernameUnavailableException;
import com.lvfast.streaming.library.LibraryValidationException;
import com.lvfast.streaming.media.MediaAssetNotFoundException;
import com.lvfast.streaming.media.job.JobStateException;
import com.lvfast.streaming.media.job.LeaseLostException;
import com.lvfast.streaming.media.job.MediaJobNotFoundException;
import com.lvfast.streaming.media.storage.StorageUnavailableException;
import com.lvfast.streaming.media.upload.MediaValidationException;
import com.lvfast.streaming.media.upload.UploadNotFoundException;
import com.lvfast.streaming.media.upload.UploadSizeExceededException;
import com.lvfast.streaming.media.upload.UploadStateException;
import com.lvfast.streaming.playback.PlaybackSessionException;
import com.lvfast.streaming.playback.ProgressValidationException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiExceptionHandler {

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail requestParameterTypeMismatch(
            MethodArgumentTypeMismatchException exception, HttpServletRequest request) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "Request parameter '" + exception.getName() + "' has an invalid value",
                request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail unreadableRequestBody(
            HttpMessageNotReadableException exception, HttpServletRequest request) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "Request body is malformed or contains an invalid value",
                request);
    }

    @ExceptionHandler(CatalogValidationException.class)
    public ProblemDetail catalogValidation(CatalogValidationException exception, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", exception.getMessage(), request);
    }

    @ExceptionHandler(LibraryValidationException.class)
    public ProblemDetail libraryValidation(LibraryValidationException exception, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", exception.getMessage(), request);
    }

    @ExceptionHandler(ProgressValidationException.class)
    public ProblemDetail progressValidation(ProgressValidationException exception, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", exception.getMessage(), request);
    }

    @ExceptionHandler(MovieNotFoundException.class)
    public ProblemDetail movieNotFound(MovieNotFoundException exception, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "MOVIE_NOT_FOUND", exception.getMessage(), request);
    }

    @ExceptionHandler(IdentityValidationException.class)
    public ProblemDetail identityValidation(
            IdentityValidationException exception, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", exception.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail beanValidation(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        ProblemDetail problem = problem(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "One or more request fields are invalid",
                request);
        List<Map<String, String>> fields = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of(
                        "field", error.getField(),
                        "code", error.getCode() == null ? "INVALID" : error.getCode(),
                        "message", error.getDefaultMessage() == null ? "invalid value" : error.getDefaultMessage()))
                .toList();
        problem.setProperty("fieldErrors", fields);
        return problem;
    }

    @ExceptionHandler(UsernameUnavailableException.class)
    public ProblemDetail usernameUnavailable(
            UsernameUnavailableException exception, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "USERNAME_UNAVAILABLE", exception.getMessage(), request);
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ProblemDetail invalidCredentials(
            InvalidCredentialsException exception, HttpServletRequest request) {
        return problem(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_FAILED", exception.getMessage(), request);
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ProblemDetail invalidRefresh(
            InvalidRefreshTokenException exception, HttpServletRequest request) {
        return problem(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", exception.getMessage(), request);
    }

    @ExceptionHandler(RefreshTokenReuseException.class)
    public ProblemDetail refreshReuse(
            RefreshTokenReuseException exception, HttpServletRequest request) {
        return problem(
                HttpStatus.UNAUTHORIZED,
                "REFRESH_TOKEN_REUSE",
                "The refresh session was revoked because token reuse was detected",
                request);
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ProblemDetail rateLimited(
            RateLimitExceededException exception, HttpServletRequest request) {
        return problem(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", exception.getMessage(), request);
    }

    @ExceptionHandler(AdminValidationException.class)
    public ProblemDetail adminValidation(AdminValidationException exception, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", exception.getMessage(), request);
    }

    @ExceptionHandler(SlugUnavailableException.class)
    public ProblemDetail slugUnavailable(SlugUnavailableException exception, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "SLUG_UNAVAILABLE", exception.getMessage(), request);
    }

    @ExceptionHandler(StaleRevisionException.class)
    public ProblemDetail staleRevision(StaleRevisionException exception, HttpServletRequest request) {
        return problem(HttpStatus.PRECONDITION_FAILED, "STALE_REVISION", exception.getMessage(), request);
    }

    @ExceptionHandler(IfMatchRequiredException.class)
    public ProblemDetail ifMatchRequired(IfMatchRequiredException exception, HttpServletRequest request) {
        return problem(HttpStatus.PRECONDITION_REQUIRED, "IF_MATCH_REQUIRED", exception.getMessage(), request);
    }

    @ExceptionHandler(MediaValidationException.class)
    public ProblemDetail mediaValidation(MediaValidationException exception, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", exception.getMessage(), request);
    }

    @ExceptionHandler(UploadSizeExceededException.class)
    public ProblemDetail uploadSizeExceeded(UploadSizeExceededException exception, HttpServletRequest request) {
        return problem(HttpStatus.PAYLOAD_TOO_LARGE, "UPLOAD_SIZE_EXCEEDED", exception.getMessage(), request);
    }

    @ExceptionHandler(UploadNotFoundException.class)
    public ProblemDetail uploadNotFound(UploadNotFoundException exception, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "UPLOAD_NOT_FOUND", exception.getMessage(), request);
    }

    @ExceptionHandler(UploadStateException.class)
    public ProblemDetail uploadState(UploadStateException exception, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "UPLOAD_STATE_CONFLICT", exception.getMessage(), request);
    }

    @ExceptionHandler(LeaseLostException.class)
    public ProblemDetail leaseLost(LeaseLostException exception, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "LEASE_LOST", exception.getMessage(), request);
    }

    @ExceptionHandler(MediaJobNotFoundException.class)
    public ProblemDetail mediaJobNotFound(MediaJobNotFoundException exception, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "JOB_NOT_FOUND", exception.getMessage(), request);
    }

    @ExceptionHandler(MediaAssetNotFoundException.class)
    public ProblemDetail mediaAssetNotFound(MediaAssetNotFoundException exception, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "ASSET_NOT_FOUND", exception.getMessage(), request);
    }

    @ExceptionHandler(JobStateException.class)
    public ProblemDetail jobState(JobStateException exception, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "JOB_STATE_CONFLICT", exception.getMessage(), request);
    }

    @ExceptionHandler(StorageUnavailableException.class)
    public ProblemDetail storageUnavailable(StorageUnavailableException exception, HttpServletRequest request) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE", exception.getMessage(), request);
    }

    @ExceptionHandler(PublicationRejectedException.class)
    public ProblemDetail publicationRejected(
            PublicationRejectedException exception, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "PUBLICATION_REJECTED", exception.getMessage(), request);
    }

    @ExceptionHandler(PlaybackSessionException.class)
    public ProblemDetail playbackSession(
            PlaybackSessionException exception, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "PLAYBACK_SESSION_NOT_FOUND", exception.getMessage(), request);
    }

    private ProblemDetail problem(
            HttpStatus status, String code, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        String typeCode = "VALIDATION_FAILED".equals(code) ? "VALIDATION_ERROR" : code;
        problem.setType(URI.create("urn:lvfast:problem:" + typeCode.toLowerCase().replace('_', '-')));
        String requestUri = request.getRequestURI();
        if (requestUri != null && !requestUri.isBlank()) {
            problem.setInstance(URI.create(requestUri));
        }
        Object requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        problem.setProperty("requestId", requestId == null ? UUID.randomUUID().toString() : requestId.toString());
        problem.setProperty("code", code);
        return problem;
    }
}
