package com.infomedia.abacox.telephonypricing.component.easyhttp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;

import java.util.concurrent.TimeUnit;

/**
 * A configurable factory for creating EasyHttp request builders.
 * An instance of this class holds a specific OkHttpClient configuration
 * (timeouts, interceptors, etc.).
 *
 * Use the static EasyHttp.url() for simple requests, or build an EasyHttpClient
 * instance for custom, reusable configurations.
 */
public class EasyHttpClient {

    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;

    private EasyHttpClient(OkHttpClient okHttpClient, ObjectMapper objectMapper) {
        this.okHttpClient = okHttpClient;
        this.objectMapper = objectMapper;
    }

    // --- Accessors ---
    public OkHttpClient getOkHttpClient() {
        return okHttpClient;
    }

    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    /**
     * Starts building a request for the given URL using this client's configuration.
     * @param url The target URL.
     * @return A new EasyHttp request builder.
     */
    public EasyHttp url(String url) {
        return new EasyHttp(url, this);
    }

    // --- Static Default Instance ---
    private static final class DefaultHolder {
        static final EasyHttpClient INSTANCE = new EasyHttpClient.Builder().build();
    }

    /**
     * Gets the default, shared instance of the client.
     */
    public static EasyHttpClient getDefaultInstance() {
        return DefaultHolder.INSTANCE;
    }

    /**
     * Creates a new builder for a custom EasyHttpClient.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Defines the logging verbosity.
     */
    public enum LoggingLevel {
        NONE, BASIC, HEADERS, BODY
    }

    /**
     * Builder for creating a custom EasyHttpClient.
     */
    public static class Builder {
        private final OkHttpClient.Builder clientBuilder;
        private final HttpLoggingInterceptor loggingInterceptor;
        private ObjectMapper customMapper;

        public Builder() {
            this.clientBuilder = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS);
            
            this.loggingInterceptor = new HttpLoggingInterceptor();
            this.loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.NONE);
            this.clientBuilder.addInterceptor(loggingInterceptor);
        }

        public Builder connectTimeout(long timeout, TimeUnit unit) {
            clientBuilder.connectTimeout(timeout, unit);
            return this;
        }

        public Builder readTimeout(long timeout, TimeUnit unit) {
            clientBuilder.readTimeout(timeout, unit);
            return this;
        }

        /**
         * Adds an application-level interceptor.
         */
        public Builder addInterceptor(Interceptor interceptor) {
            clientBuilder.addInterceptor(interceptor);
            return this;
        }

        /**
         * Adds a network-level interceptor.
         */
        public Builder addNetworkInterceptor(Interceptor interceptor) {
            clientBuilder.addNetworkInterceptor(interceptor);
            return this;
        }

        /**
         * Sets the logging level for HTTP requests and responses.
         */
        public Builder loggingLevel(LoggingLevel level) {
            HttpLoggingInterceptor.Level okhttpLevel;
            switch (level) {
                case BASIC:   okhttpLevel = HttpLoggingInterceptor.Level.BASIC;   break;
                case HEADERS: okhttpLevel = HttpLoggingInterceptor.Level.HEADERS; break;
                case BODY:    okhttpLevel = HttpLoggingInterceptor.Level.BODY;    break;
                case NONE:
                default:      okhttpLevel = HttpLoggingInterceptor.Level.NONE;    break;
            }
            this.loggingInterceptor.setLevel(okhttpLevel);
            return this;
        }

        /**
         * Provide a custom Jackson ObjectMapper for this client instance.
         */
        public Builder objectMapper(ObjectMapper mapper) {
            this.customMapper = mapper;
            return this;
        }

        /**
         * Builds and returns the configured EasyHttpClient.
         */
        public EasyHttpClient build() {
            ObjectMapper mapper = (this.customMapper != null) ? this.customMapper : new ObjectMapper();
            mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
            mapper.findAndRegisterModules();
            mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
            return new EasyHttpClient(clientBuilder.build(), mapper);
        }
    }
}