package com.infomedia.abacox.telephonypricing.component.utils;

import net.jpountz.xxhash.StreamingXXHash64;
import net.jpountz.xxhash.XXHashFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;

public class XXHash64Util {

    // "fastestInstance()" will use JNI (C++ speed) if available, 
    // otherwise falls back to pure Java automatically.
    private static final XXHashFactory FACTORY = XXHashFactory.fastestInstance();
    
    // 8KB Buffer is standard for disk IO
    private static final int BUFFER_SIZE = 8192;
    
    // Seed 0 is the standard default for xxHash
    private static final long SEED = 0L;

    /**
     * Hash a byte array directly
     * @param data byte array to hash
     * @return long (64-bit hash)
     */
    public static long hash(byte[] data) {
        // Use the non-streaming instance for byte arrays (faster)
        return FACTORY.hash64().hash(data, 0, data.length, SEED);
    }

    /**
     * Hash a file
     * @param file file to hash
     * @return long (64-bit hash)
     */
    public static long hash(File file) throws IOException {
        return hash(file.toPath());
    }

    /**
     * Hash a file by path
     * @param filePath path to file
     * @return long (64-bit hash)
     */
    public static long hash(Path filePath) throws IOException {
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
     * @return long (64-bit hash)
     */
    public static long hash(InputStream inputStream) throws IOException {
        StreamingXXHash64 hasher = FACTORY.newStreamingHash64(SEED);
        byte[] buffer = new byte[BUFFER_SIZE];
        int bytesRead;

        while ((bytesRead = inputStream.read(buffer)) != -1) {
            hasher.update(buffer, 0, bytesRead);
        }

        return hasher.getValue();
    }
}