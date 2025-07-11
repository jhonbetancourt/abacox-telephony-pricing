package com.infomedia.abacox.telephonypricing.component.easyhttp;

import java.io.IOException;

/**
 * Custom exception for handling HTTP and network-related errors from EasyHttp.
 */
public class EasyHttpException extends RuntimeException {
    private final int statusCode;
    private final String responseBody;

    /**
     * Constructor for HTTP errors (e.g., 404, 500) that include a response body.
     * @param message The error message from the response.
     * @param statusCode The HTTP status code.
     * @param responseBody The body of the error response.
     */
    public EasyHttpException(String message, int statusCode, String responseBody) {
        super(message);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    /**
     * Constructor for network or parsing errors.
     * @param message A descriptive message of the failure.
     * @param cause The underlying exception.
     */
    public EasyHttpException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = -1; // Indicates a non-HTTP error
        this.responseBody = null;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getResponseBody() {
        return responseBody;
    }

    @Override
    public String getMessage() {
        if (statusCode > 0) {
            return String.format("HTTP Error: %d %s\nResponse: %s", statusCode, super.getMessage(), responseBody);
        }
        return super.getMessage();
    }
}