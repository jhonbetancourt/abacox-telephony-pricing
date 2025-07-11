package com.infomedia.abacox.telephonypricing.component.easyhttp;

import okhttp3.Response;

/**
 * A generic callback for handling asynchronous EasyHttp responses.
 * @param <T> The expected type of the result. For async calls, this is typically the raw okhttp3.Response.
 */
@FunctionalInterface
public interface EasyCallback<T> {
    /**
     * Called when the HTTP request is complete.
     * @param result The result of the operation (e.g., an okhttp3.Response), or null on failure.
     * @param error An EasyHttpException if an error occurred, or null on success.
     */
    void onComplete(T result, EasyHttpException error);
}