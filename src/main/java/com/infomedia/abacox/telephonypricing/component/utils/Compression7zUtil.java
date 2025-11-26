package com.infomedia.abacox.telephonypricing.component.utils;

import lombok.extern.log4j.Log4j2;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.XZInputStream;
import org.tukaani.xz.XZOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

@Log4j2
public class Compression7zUtil {

    /**
     * Compresses data using LZMA2 (7z) with maximum preset (level 9)
     * 
     * @param data The raw data to compress
     * @return Compressed data as byte array
     * @throws IOException if compression fails
     */
    public static byte[] compress(byte[] data) throws IOException {
        if (data == null || data.length == 0) {
            return data;
        }

        try (ByteArrayOutputStream byteOut = new ByteArrayOutputStream()) {
            // LZMA2Options.PRESET_MAX is equivalent to 7z maximum preset (level 9)
            LZMA2Options options = new LZMA2Options(LZMA2Options.PRESET_MAX);
            
            try (XZOutputStream xzOut = new XZOutputStream(byteOut, options)) {
                xzOut.write(data);
                xzOut.finish();
            }
            
            byte[] compressed = byteOut.toByteArray();
            log.trace("Compressed {} bytes to {} bytes (ratio: {%.2f}%)", 
                     data.length, compressed.length, 
                     (compressed.length * 100.0 / data.length));
            return compressed;
        }
    }

    /**
     * Decompresses LZMA2 compressed data
     * 
     * @param compressedData The compressed data
     * @return Decompressed data as byte array
     * @throws IOException if decompression fails
     */
    public static byte[] decompress(byte[] compressedData) throws IOException {
        if (compressedData == null || compressedData.length == 0) {
            return compressedData;
        }

        try (ByteArrayInputStream byteIn = new ByteArrayInputStream(compressedData);
             XZInputStream xzIn = new XZInputStream(byteIn);
             ByteArrayOutputStream byteOut = new ByteArrayOutputStream()) {
            
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = xzIn.read(buffer)) != -1) {
                byteOut.write(buffer, 0, bytesRead);
            }
            
            return byteOut.toByteArray();
        }
    }

    /**
     * Convenience method to compress a String
     */
    public static byte[] compressString(String str) throws IOException {
        if (str == null || str.isEmpty()) {
            return new byte[0];
        }
        return compress(str.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Convenience method to decompress to a String
     */
    public static String decompressToString(byte[] compressedData) throws IOException {
        if (compressedData == null || compressedData.length == 0) {
            return "";
        }
        byte[] decompressed = decompress(compressedData);
        return new String(decompressed, java.nio.charset.StandardCharsets.UTF_8);
    }
}