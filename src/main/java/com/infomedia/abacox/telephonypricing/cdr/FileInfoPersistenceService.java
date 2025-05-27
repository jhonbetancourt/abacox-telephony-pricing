// File: com/infomedia/abacox/telephonypricing/cdr/FileInfoPersistenceService.java
package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.FileInfo;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@Log4j2
public class FileInfoPersistenceService {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Creates a new FileInfo record or retrieves an existing one based on a checksum.
     * This method should run in its own transaction or ensure the entity is flushed
     * if part of a larger transaction, so its ID is available to subsequent separate transactions.
     *
     * For stream processing, parentId is typically the plantTypeId or commLocationId.
     * Type is a descriptor like "ROUTED_STREAM" or "PRE_ROUTED_STREAM".
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW) // Ensures this commits independently
    public FileInfo createOrGetFileInfo(String filename, Long parentId, String type) {
        // For streams, size might not be easily available without consuming it.
        // A simple approach is to use filename and parentId as a key.
        // PHP's Archivo_Local_Info creates a hash based on path, size, modtime.
        // For a stream, we use filename + parentId + current time to ensure a new record for each processing attempt of a stream.

        String uniqueKey = filename + "|" + (parentId != null ? parentId : "null") + "|" + System.currentTimeMillis();
        String checksum = HashUtil.sha256(uniqueKey);
        log.debug("Attempting to create/get FileInfo. Filename: {}, ParentId: {}, Type: {}, Checksum: {}", filename, parentId, type, checksum);


        FileInfo fileInfo = findByChecksumInternal(checksum); // Internal call to avoid new transaction
        if (fileInfo == null) {
            fileInfo = new FileInfo();
            fileInfo.setFilename(filename.length() > 255 ? filename.substring(0, 255) : filename);
            fileInfo.setParentId(parentId != null ? parentId.intValue() : 0);
            fileInfo.setType(type.length() > 64 ? type.substring(0, 64) : type);
            fileInfo.setDate(LocalDateTime.now()); // Processing time
            fileInfo.setChecksum(checksum);
            fileInfo.setSize(0); // Size unknown for stream without full read
            fileInfo.setReferenceId(0);
            fileInfo.setDirectory(""); // Not applicable for stream

            entityManager.persist(fileInfo);
            entityManager.flush(); // Ensure it's written to DB and ID is generated before transaction ends
            log.info("Created and flushed new FileInfo record with ID: {} for file: {}", fileInfo.getId(), filename);
        } else {
            log.info("Found existing FileInfo record with ID: {} for checksum: {}", fileInfo.getId(), checksum);
        }
        return fileInfo;
    }

    // Non-transactional internal method for lookup, to be called by the @Transactional createOrGetFileInfo
    private FileInfo findByChecksumInternal(String checksum) {
        try {
            return entityManager.createQuery("SELECT fi FROM FileInfo fi WHERE fi.checksum = :checksum", FileInfo.class)
                    .setParameter("checksum", checksum)
                    .getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }

    // Kept for other potential uses, but createOrGetFileInfo is primary for streams
    @Transactional(readOnly = true)
    public FileInfo findById(Integer fileInfoId) {
        if (fileInfoId == null) return null;
        return entityManager.find(FileInfo.class, fileInfoId);
    }
}