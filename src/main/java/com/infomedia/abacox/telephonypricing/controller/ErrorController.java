package com.infomedia.abacox.telephonypricing.controller;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.error.ErrorAttributes;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.ServletWebRequest;

import java.net.URI;
import java.time.Instant;

@Hidden
@RestController
@RequestMapping("${server.error.path:${error.path:/error}}")
public class ErrorController implements org.springframework.boot.web.servlet.error.ErrorController {

    private final ErrorAttributes errorAttributes;

    public ErrorController(ErrorAttributes errorAttributes) {
        this.errorAttributes = errorAttributes;
    }

    @RequestMapping
    public ResponseEntity<ProblemDetail> error(HttpServletRequest request) {
        ServletWebRequest webRequest = new ServletWebRequest(request);
        Throwable error = errorAttributes.getError(webRequest);
        int sc = (Integer) request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        HttpStatus status = HttpStatus.valueOf(sc);

        String originalPath = (String) request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        if (originalPath == null) {
            originalPath = request.getRequestURI(); // fallback
        }

        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                status,
                error != null ? error.getMessage() : status.getReasonPhrase()
        );
        pd.setType(URI.create(toHyphenSeparatedLowercase("status-" + status.getReasonPhrase())));
        pd.setTitle(status.getReasonPhrase());
        pd.setInstance(URI.create(originalPath));
        pd.setProperty("timestamp", Instant.now().toString());

        return ResponseEntity
                .status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(pd);
    }

    public static String toHyphenSeparatedLowercase(String input) {
        if (input == null) return "";

        // Remove leading/trailing whitespace and convert to lowercase
        String result = input.trim().toLowerCase();

        // Replace all non-alphanumeric characters (excluding spaces and hyphens) with an empty string
        result = result.replaceAll("[^a-z0-9\\s-]", "");

        // Replace one or more spaces or hyphens with a single hyphen
        result = result.replaceAll("[\\s-]+", "-");

        return result;
    }
}
