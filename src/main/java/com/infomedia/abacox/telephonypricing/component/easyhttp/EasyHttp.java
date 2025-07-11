package com.infomedia.abacox.telephonypricing.component.easyhttp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import okhttp3.*;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A fluent builder for constructing and executing a single HTTP request.
 * An instance of this class is created via an EasyHttpClient.
 */
public class EasyHttp {

    private final EasyHttpClient client;
    private final Request.Builder requestBuilder;
    private final HttpUrl.Builder urlBuilder;
    private RequestBody requestBody;
    private MultipartBody.Builder multipartBuilder;

    // Package-private constructor, should only be created by EasyHttpClient
    EasyHttp(String url, EasyHttpClient client) {
        this.client = client;
        this.requestBuilder = new Request.Builder();
        HttpUrl parsedUrl = HttpUrl.parse(url);
        if (parsedUrl == null) {
            throw new IllegalArgumentException("Invalid URL: " + url);
        }
        this.urlBuilder = parsedUrl.newBuilder();
    }

    /**
     * Static convenience method to start building a request using the default client.
     * @param url The target URL.
     * @return A new EasyHttp request builder.
     */
    public static EasyHttp url(String url) {
        return EasyHttpClient.getDefaultInstance().url(url);
    }

    // --- Request Configuration Methods (Builder) ---
    public EasyHttp header(String name, String value) {
        requestBuilder.header(name, value);
        return this;
    }

    public EasyHttp headers(Map<String, String> headers) {
        headers.forEach(requestBuilder::header);
        return this;
    }

    public EasyHttp queryParam(String name, String value) {
        urlBuilder.addQueryParameter(name, value);
        return this;
    }

    public EasyHttp queryParams(Map<String, String> params) {
        params.forEach(urlBuilder::addQueryParameter);
        return this;
    }

    public EasyHttp json(Object object) {
        if (this.multipartBuilder != null) {
            throw new IllegalStateException("Cannot set JSON body when a multipart body is already being built.");
        }
        try {
            String jsonString = client.getObjectMapper().writeValueAsString(object);
            this.requestBody = RequestBody.create(jsonString, MediaType.get("application/json; charset=utf-8"));
            return this;
        } catch (JsonProcessingException e) {
            throw new EasyHttpException("Failed to serialize object to JSON", e);
        }
    }

    public EasyHttp form(Map<String, String> formData) {
        if (this.multipartBuilder != null) {
            throw new IllegalStateException("Cannot set form body when a multipart body is already being built. Use formPart() instead.");
        }
        FormBody.Builder formBuilder = new FormBody.Builder();
        formData.forEach(formBuilder::add);
        this.requestBody = formBuilder.build();
        return this;
    }

    public EasyHttp formPart(String name, String value) {
        initMultipart();
        multipartBuilder.addFormDataPart(name, value);
        return this;
    }

    public EasyHttp file(String name, String filename, File file) {
        // A real app should use a more robust MIME type detector
        MediaType mediaType = MediaType.parse(MimeTypeUtil.getMimeType(filename));
        return file(name, filename, file, mediaType);
    }

    public EasyHttp file(String name, String filename, File file, MediaType mediaType) {
        initMultipart();
        RequestBody fileBody = RequestBody.create(file, mediaType);
        multipartBuilder.addFormDataPart(name, filename, fileBody);
        return this;
    }

    private void initMultipart() {
        if (this.requestBody != null) {
            throw new IllegalStateException("Cannot build a multipart body when a plain body (JSON/Form) is already set.");
        }
        if (this.multipartBuilder == null) {
            this.multipartBuilder = new MultipartBody.Builder().setType(MultipartBody.FORM);
        }
    }

    // --- Execution Methods (Terminal) ---

    private Request build(String method) {
        if (multipartBuilder != null) {
            this.requestBody = multipartBuilder.build();
        }
        return requestBuilder.url(urlBuilder.build()).method(method, requestBody).build();
    }

    // Synchronous methods
    public ResponseExecutor get() { return new ResponseExecutor(build("GET"), client); }
    public ResponseExecutor post() { return new ResponseExecutor(build("POST"), client); }
    public ResponseExecutor put() { return new ResponseExecutor(build("PUT"), client); }
    public ResponseExecutor delete() { return new ResponseExecutor(build("DELETE"), client); }
    public ResponseExecutor patch() { return new ResponseExecutor(build("PATCH"), client); }

    // Asynchronous methods
    public void get(EasyCallback<Response> callback) { enqueue(build("GET"), callback); }
    public void post(EasyCallback<Response> callback) { enqueue(build("POST"), callback); }
    public void put(EasyCallback<Response> callback) { enqueue(build("PUT"), callback); }
    public void delete(EasyCallback<Response> callback) { enqueue(build("DELETE"), callback); }
    public void patch(EasyCallback<Response> callback) { enqueue(build("PATCH"), callback); }

    private void enqueue(Request request, EasyCallback<Response> callback) {
        client.getOkHttpClient().newCall(request).enqueue(new Callback() {
            @Override
            public void onResponse(Call call, Response response) {
                callback.onComplete(response, null);
            }
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onComplete(null, new EasyHttpException("Network request failed", e));
            }
        });
    }

    /**
     * Inner class to handle synchronous response processing.
     */
    public static class ResponseExecutor {
        private final Request request;
        private final EasyHttpClient client;

        private ResponseExecutor(Request request, EasyHttpClient client) {
            this.request = request;
            this.client = client;
        }

        public Response execute() throws EasyHttpException {
            try {
                return client.getOkHttpClient().newCall(request).execute();
            } catch (IOException e) {
                throw new EasyHttpException("Network request failed", e);
            }
        }

        public String asString() throws EasyHttpException {
            try (Response response = execute()) {
                String bodyString = response.body().string();
                if (!response.isSuccessful()) {
                    throw new EasyHttpException(response.message(), response.code(), bodyString);
                }
                return bodyString;
            } catch (IOException e) {
                throw new EasyHttpException("Failed to read response body", e);
            }
        }

        public <T> T asObject(Class<T> clazz) throws EasyHttpException {
            String bodyString = asString();
            try {
                return client.getObjectMapper().readValue(bodyString, clazz);
            } catch (JsonProcessingException e) {
                throw new EasyHttpException("Failed to map JSON response to " + clazz.getSimpleName(), e);
            }
        }

        public <T> T asObject(TypeReference<T> typeRef) throws EasyHttpException {
            String bodyString = asString();
            try {
                return client.getObjectMapper().readValue(bodyString, typeRef);
            } catch (JsonProcessingException e) {
                throw new EasyHttpException("Failed to map JSON response to " + typeRef.getType().getTypeName(), e);
            }
        }

        public void asInputStream(Consumer<InputStream> consumer) throws EasyHttpException {
            try (Response response = execute()) {
                if (!response.isSuccessful()) {
                    String bodyString = response.body().string();
                    throw new EasyHttpException(response.message(), response.code(), bodyString);
                }
                try (InputStream inputStream = response.body().byteStream()) {
                    consumer.accept(inputStream);
                }
            } catch (IOException e) {
                throw new EasyHttpException("Failed to read response body stream", e);
            }
        }
    }
}