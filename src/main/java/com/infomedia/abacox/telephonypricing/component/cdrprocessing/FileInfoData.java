package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

/**
 * A record to hold the original filename and decompressed content of a file.
 *
 * @param filename The original name of the file.
 * @param content  The raw, decompressed byte content of the file.
 */
public record FileInfoData(String filename, byte[] content) {
}