package com.infomedia.abacox.telephonypricing.config;


import com.infomedia.abacox.telephonypricing.exception.*;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ValidationException;
import lombok.extern.log4j.Log4j2;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.stream.Collectors;

@Log4j2
@ControllerAdvice
@Configuration
public class ExceptionHandlerConfig extends ResponseEntityExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleResourceNotFoundException(ResourceNotFoundException ex) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problemDetail.setTitle("Resource Not Found");
        problemDetail.setType(URI.create("resource-not-found"));
        problemDetail.setProperty("timestamp", LocalDateTime.now());
        return problemDetail;
    }

    @ExceptionHandler(ResourceAlreadyExistsException.class)
    public ProblemDetail handleResourceAlreadyExistsException(ResourceAlreadyExistsException ex) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problemDetail.setTitle("Resource Already Exists");
        problemDetail.setType(URI.create("resource-already-exists"));
        problemDetail.setProperty("timestamp", LocalDateTime.now());
        return problemDetail;
    }

    @ExceptionHandler(RemoteServiceException.class)
    public ProblemDetail handleRemoteServiceException(RemoteServiceException ex) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage());
        problemDetail.setTitle("Remote Service Error");
        problemDetail.setType(URI.create("remote-service-error"));
        problemDetail.setProperty("timestamp", LocalDateTime.now());
        return problemDetail;
    }

    @ExceptionHandler(ResourceDeletionException.class)
    public ProblemDetail handleResourceDeletionException(ResourceDeletionException ex) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problemDetail.setTitle("Resource Deletion Error");
        problemDetail.setType(URI.create("resource-deletion-error"));
        problemDetail.setProperty("timestamp", LocalDateTime.now());
        return problemDetail;
    }

    @ExceptionHandler(ResourceDisabledException.class)
    public ProblemDetail handleResourceDisabledException(ResourceDisabledException ex) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problemDetail.setTitle("Resource Disabled");
        problemDetail.setType(URI.create("resource-disabled"));
        problemDetail.setProperty("timestamp", LocalDateTime.now());
        return problemDetail;
    }

    @Override
    protected ResponseEntity<Object> handleHttpRequestMethodNotSupported(
            org.springframework.web.HttpRequestMethodNotSupportedException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.METHOD_NOT_ALLOWED, ex.getMessage());
        problemDetail.setTitle("Method Not Allowed");
        problemDetail.setType(URI.create("method-not-allowed"));
        problemDetail.setProperty("timestamp", LocalDateTime.now());
        return new ResponseEntity<>(problemDetail, headers, status);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMediaTypeNotSupported(
            org.springframework.web.HttpMediaTypeNotSupportedException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ex.getMessage());
        problemDetail.setTitle("Unsupported Media Type");
        problemDetail.setType(URI.create("unsupported-media-type"));
        problemDetail.setProperty("timestamp", LocalDateTime.now());
        return new ResponseEntity<>(problemDetail, headers, status);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMediaTypeNotAcceptable(
            org.springframework.web.HttpMediaTypeNotAcceptableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_ACCEPTABLE, ex.getMessage());
        problemDetail.setTitle("Not Acceptable Media Type");
        problemDetail.setType(URI.create("not-acceptable-media-type"));
        problemDetail.setProperty("timestamp", LocalDateTime.now());
        return new ResponseEntity<>(problemDetail, headers, status);
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(
            MissingServletRequestParameterException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problemDetail.setTitle("Missing Request Parameter");
        problemDetail.setType(URI.create("missing-parameter"));
        problemDetail.setProperty("timestamp", LocalDateTime.now());
        return new ResponseEntity<>(problemDetail, headers, status);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleMethodArgumentTypeMismatch(MethodArgumentTypeMismatchException ex) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problemDetail.setTitle("Method Argument Type Mismatch");
        problemDetail.setType(URI.create("argument-type-mismatch"));
        problemDetail.setProperty("timestamp", LocalDateTime.now());
        return problemDetail;
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        String errorMessage = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, errorMessage);
        problemDetail.setTitle("Validation Error");
        problemDetail.setType(URI.create("validation-error"));
        problemDetail.setProperty("timestamp", LocalDateTime.now());
        return new ResponseEntity<>(problemDetail, headers, status);
    }

    @ExceptionHandler(ValidationException.class)
    public ProblemDetail handleValidationException(ValidationException ex) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problemDetail.setTitle("Validation Error");
        problemDetail.setType(URI.create("validation-error"));
        problemDetail.setProperty("timestamp", LocalDateTime.now());
        return problemDetail;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException ex) {
        String errorMessage = ex.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .collect(Collectors.joining(", "));
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, errorMessage);
        problemDetail.setTitle("Validation Error");
        problemDetail.setType(URI.create("validation-error"));
        problemDetail.setProperty("timestamp", LocalDateTime.now());
        return problemDetail;
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ProblemDetail handleBadCredentialsException(BadCredentialsException ex) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
        problemDetail.setTitle("Bad Credentials");
        problemDetail.setType(URI.create("bad-credentials"));
        problemDetail.setProperty("timestamp", LocalDateTime.now());
        return problemDetail;
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrityViolationException(DataIntegrityViolationException ex) {
        if(NestedExceptionUtils.getRootCause(ex) instanceof PSQLException psqlException){
            ServerErrorMessage serverErrorMessage = psqlException.getServerErrorMessage();
            if(serverErrorMessage!=null){
                ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT
                        , serverErrorMessage.getMessage()+": "+serverErrorMessage.getDetail());
                problemDetail.setTitle("Data Integrity Violation");
                problemDetail.setType(URI.create("data-integrity-violation"));
                problemDetail.setProperty("timestamp", LocalDateTime.now());
                return problemDetail;
            }
        }
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problemDetail.setTitle("Data Integrity Violation");
        problemDetail.setType(URI.create("data-integrity-violation"));
        problemDetail.setProperty("timestamp", LocalDateTime.now());
        return problemDetail;
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex, HttpHeaders headers
            , HttpStatusCode status, WebRequest request) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problemDetail.setTitle("Message Not Readable");
        problemDetail.setType(URI.create("message-not-readable"));
        problemDetail.setProperty("timestamp", LocalDateTime.now());
        return new ResponseEntity<>(problemDetail, headers, status);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        log.error(ex);
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR
                , "An unexpected error occurred");
        problemDetail.setTitle("Internal Server Error");
        problemDetail.setType(URI.create("internal-error"));
        problemDetail.setProperty("timestamp", LocalDateTime.now());
        problemDetail.setStatus(statusCode.value());
        return new ResponseEntity<>(problemDetail, headers, statusCode);
    }


}