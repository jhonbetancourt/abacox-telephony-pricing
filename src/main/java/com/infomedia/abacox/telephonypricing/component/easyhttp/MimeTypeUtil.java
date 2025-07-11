package com.infomedia.abacox.telephonypricing.component.easyhttp;

import java.net.URLConnection;

/**
 * A utility class to determine the MIME type from a filename.
 * It dynamically checks for the presence of the Spring Framework on the classpath.
 * - If Spring is present, it uses the more comprehensive MediaTypeFactory.
 * - If not, it falls back to the standard but more limited URLConnection.
 */
public final class MimeTypeUtil {

    /**
     * A flag that is set once to determine if Spring Framework is available.
     * The static block ensures this check is performed only once when the class is loaded.
     */
    private static final boolean isSpringPresent;

    static {
        boolean springAvailable;
        try {
            // Try to load a core Spring class. If it succeeds, Spring is on the classpath.
            Class.forName("org.springframework.http.MediaTypeFactory");
            springAvailable = true;
        } catch (ClassNotFoundException e) {
            // If the class is not found, Spring is not on the classpath.
            springAvailable = false;
        }
        isSpringPresent = springAvailable;
    }

    // Private constructor to prevent instantiation of this utility class.
    private MimeTypeUtil() {}

    /**
     * Gets the MIME type string for a given filename.
     *
     * This method intelligently delegates to the best available resolver.
     *
     * @param filename The name of the file (e.g., "document.pdf", "image.png").
     * @return The MIME type as a String (e.g., "application/pdf"), or "application/octet-stream"
     *         if the type cannot be determined.
     */
    public static String getMimeType(String filename) {
        String mimeType;

        if (isSpringPresent) {
            // If Spring is available, use the superior SpringMimeTypeResolver.
            mimeType = SpringMimeTypeResolver.getMimeType(filename);
        } else {
            // Otherwise, fall back to the standard JDK implementation.
            mimeType = URLConnection.getFileNameMap().getContentTypeFor(filename);
        }

        // Provide a standard fallback for unknown types.
        return (mimeType != null) ? mimeType : "application/octet-stream";
    }

    /**
     * An inner static class that handles the Spring-specific logic.
     *
     * This class is only loaded by the JVM if `isSpringPresent` is true,
     * thus preventing a ClassNotFoundException in a non-Spring environment.
     */
    private static class SpringMimeTypeResolver {
        public static String getMimeType(String filename) {
            // This code will only ever be executed if Spring classes are available.
            return org.springframework.http.MediaTypeFactory
                    .getMediaType(filename)
                    .map(Object::toString) // Convert MediaType object to its String representation
                    .orElse(null); // Return null if Optional is empty, to be handled by the outer method.
        }
    }
}