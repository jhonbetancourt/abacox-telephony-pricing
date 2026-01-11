package com.infomedia.abacox.telephonypricing.component.utils;

import com.dynatrace.hash4j.hashing.*;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public class XXHash128Util {

    // The XXH3_128 hasher instance (Thread-safe and immutable)
    private static final Hasher128 HASHER = Hashing.xxh3_128();

    // 8KB Buffer is standard for disk IO
    private static final int BUFFER_SIZE = 8192;

    /**
     * Hash a byte array directly to a UUID.
     * Use this for small data already in memory.
     *
     * @param data byte array to hash
     * @return UUID representing the 128-bit hash
     */
    public static UUID hash(byte[] data) {
        HashValue128 hashValue = HASHER.hashBytesTo128Bits(data);
        return new UUID(hashValue.getMostSignificantBits(), hashValue.getLeastSignificantBits());
    }

    /**
     * Hash a file to a UUID.
     *
     * @param file file to hash
     * @return UUID representing the 128-bit hash
     */
    public static UUID hash(File file) throws IOException {
        return hash(file.toPath());
    }

    /**
     * Hash a file by path to a UUID.
     *
     * @param filePath path to file
     * @return UUID representing the 128-bit hash
     */
    public static UUID hash(Path filePath) throws IOException {
        try (InputStream in = Files.newInputStream(filePath)) {
            return hash(in);
        }
    }

    /**
     * Hash an InputStream using TRUE streaming.
     * This reads the stream in small chunks, updating the hash state incrementally.
     * It does NOT load the whole file into memory.
     *
     * @param inputStream input stream to hash
     * @return UUID representing the 128-bit hash
     */
    public static UUID hash(InputStream inputStream) throws IOException {
        // CORRECTED LINE: Use hashStream() to get a streaming object
        HashStream128 stream = HASHER.hashStream();

        byte[] buffer = new byte[BUFFER_SIZE];
        int bytesRead;

        while ((bytesRead = inputStream.read(buffer)) != -1) {
            // Feed data into the stream
            stream.putBytes(buffer, 0, bytesRead);
        }

        // Get the result
        HashValue128 hashValue = stream.get();

        // Convert to UUID
        return new UUID(hashValue.getMostSignificantBits(), hashValue.getLeastSignificantBits());
    }
    
    /**
     * Helper: Convert UUID to a clean Hex String (without hyphens).
     * Useful if you store as CHAR(32) or for logging.
     */
    public static String toHexString(UUID uuid) {
        return String.format("%016x%016x", 
            uuid.getMostSignificantBits(), 
            uuid.getLeastSignificantBits());
    }
}