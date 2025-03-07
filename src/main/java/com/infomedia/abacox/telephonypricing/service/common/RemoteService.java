package com.infomedia.abacox.telephonypricing.service.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.infomedia.abacox.telephonypricing.exception.RemoteServiceException;
import com.infomedia.abacox.telephonypricing.service.AuthService;
import lombok.Setter;
import okhttp3.*;
import okhttp3.logging.HttpLoggingInterceptor;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;

public abstract class RemoteService {
    private final AuthService authService;
    private OkHttpClient client;
    private HttpLoggingInterceptor loggingInterceptor;
    private ObjectMapper objectMapper;
    @Setter
    private String testUsername;

    protected RemoteService(AuthService authService) {
        this.authService = authService;
        this.loggingInterceptor = new HttpLoggingInterceptor();
        loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.BASIC);
        this.client = new OkHttpClient.Builder()
                .addInterceptor(loggingInterceptor)
                .addInterceptor(chain -> chain.proceed(chain.request().newBuilder()
                                .addHeader("X-Username", testUsername!=null?testUsername:authService.getUsername())
                        .build()))
                .build();
        this.objectMapper = new ObjectMapper();
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        objectMapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        objectMapper.findAndRegisterModules();
        objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    public abstract String getBaseUrl();

    protected static final String GET = "GET";
    protected static final String POST = "POST";
    protected static final String PUT = "PUT";
    protected static final String DELETE = "DELETE";
    protected static final String PATCH = "PATCH";

    protected <T> T post(Object object, String path, Class<T> clazz) {
        return newCall(object, path, POST, clazz);
    }

    protected <T> T post(Object object, String path, TypeReference<T> typeReference) {
        return newCall(object, path, POST, typeReference);
    }

    protected String post(Object object, String path) {
        return newCall(object, path, POST);
    }

    protected <T> T get(Map<String, Object> query, String path, Class<T> clazz) {
        return newCall(query, path, GET, clazz);
    }

    protected <T> T get(Map<String, Object> query, String path, TypeReference<T> typeReference) {
        return newCall(query, path, GET, typeReference);
    }

    protected String get(Map<String, Object> query, String path) {
        return newCall(query, path, GET);
    }

    protected <T> T put(Object object, String path, Class<T> clazz) {
        return newCall(object, path, PUT, clazz);
    }

    protected <T> T put(Object object, String path, TypeReference<T> typeReference) {
        return newCall(object, path, PUT, typeReference);
    }

    protected String put(Object object, String path) {
        return newCall(object, path, PUT);
    }

    protected <T> T delete(Object object, String path, Class<T> clazz) {
        return newCall(object, path, DELETE, clazz);
    }

    protected <T> T delete(Object object, String path, TypeReference<T> typeReference) {
        return newCall(object, path, DELETE, typeReference);
    }

    protected String delete(Object object, String path) {
        return newCall(object, path, DELETE);
    }

    protected <T> T patch(Object object, String path, Class<T> clazz) {
        return newCall(object, path, PATCH, clazz);
    }

    protected <T> T patch(Object object, String path, TypeReference<T> typeReference) {
        return newCall(object, path, PATCH, typeReference);
    }

    protected String patch(Object object, String path) {
        return newCall(object, path, PATCH);
    }

    protected <T> T newCall(Object object, String path, String method, Class<T> clazz) {
        if(object instanceof Map m){
            return newCall(buildRequestQuery(path, m, method), clazz);
        }else{
            return newCall(buildRequestJson(path, object, method), clazz);
        }
    }

    protected <T> T newCall(Object object, String path, String method, TypeReference<T> typeReference) {
        if(object instanceof Map m){
            return newCall(buildRequestQuery(path, m, method), typeReference);
        }else{
            return newCall(buildRequestJson(path, object, method), typeReference);
        }
    }

    protected String newCall(Object object, String path, String method) {
        if(object instanceof Map m){
            return newCall(buildRequestQuery(path, m, method));
        }else{
            return newCall(buildRequestJson(path, object, method));
        }
    }

    private Request buildRequestQuery(String path, Map<String, Object> query, String method) {

        HttpUrl.Builder urlBuilder = HttpUrl.parse(getBaseUrl() + path).newBuilder();
        query.forEach((key, value) -> {
            if(value instanceof Collection c){
                String joined = String.join(",", c);
                urlBuilder.addQueryParameter(key, joined);
            }else{
                urlBuilder.addQueryParameter(key, value.toString());
            }
        });

        Request request = new Request.Builder()
                .method(method, null)
                .url(urlBuilder.build())
                .build();
        return request;
    }

    private Request buildRequestJson(String path, Object object, String method) {
        String requestBodyString;
        try {
            requestBodyString = objectMapper.writeValueAsString(object);
        } catch (IOException e) {
            throw new RemoteServiceException("Error while serializing object to JSON", e);
        }

        RequestBody requestBody = RequestBody.create(requestBodyString, MediaType.parse("application/json"));

        Request request = new Request.Builder()
                .method(method, requestBody)
                .url(getBaseUrl() + path)
                .build();
        return request;
    }

    protected <T> T newCall(Request request, Class<T> clazz) {
        try (Response response = client.newCall(request).execute()) {
            String responseBodyString = response.body()!=null ? response.body().string() : null;
            if (response.isSuccessful()) {
                try {
                    return objectMapper.readValue(responseBodyString, clazz);
                } catch (JsonProcessingException e) {
                    throw new RemoteServiceException("Error while deserializing JSON to object", e);
                }
            }else{
                int code = response.code();
                throw new RemoteServiceException("Remote service response was not successful. Code: " + code + ", Body: " + responseBodyString);
            }
        } catch (IOException e) {
            throw new RemoteServiceException("Error while calling remote service", e);
        }
    }

    protected <T> T newCall(Request request, TypeReference<T> typeReference) {
        try (Response response = client.newCall(request).execute()) {
            String responseBodyString = response.body()!=null ? response.body().string() : null;
            if (response.isSuccessful()) {
                try {
                    return objectMapper.readValue(responseBodyString, typeReference);
                } catch (JsonProcessingException e) {
                    throw new RemoteServiceException("Error while deserializing JSON to object", e);
                }
            }else{
                int code = response.code();
                throw new RemoteServiceException("Remote service response was not successful. Code: " + code + ", Body: " + responseBodyString);
            }
        } catch (IOException e) {
            throw new RemoteServiceException("Error while calling remote service", e);
        }
    }

    protected String newCall(Request request) {
        try (Response response = client.newCall(request).execute()) {
            String responseBodyString = response.body()!=null ? response.body().string() : null;
            if (response.isSuccessful()) {
                return responseBodyString;
            }else{
                int code = response.code();
                throw new RemoteServiceException("Remote service response was not successful. Code: " + code + ", Body: " + responseBodyString);
            }
        } catch (IOException e) {
            throw new RemoteServiceException("Error while calling remote service", e);
        }
    }
}
