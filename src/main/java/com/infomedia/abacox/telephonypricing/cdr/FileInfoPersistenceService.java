package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.FileInfo;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.LocalDateTime;

@Service
@Log4j2
public class FileInfoPersistenceService {

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional
    public FileInfo createOrGetFileInfo(String filename, Long parentId, String type, InputStream inputStream) {
        // For streams, size might not be easily available without consuming it.
        // A simple approach is to use filename and parentId as a key.
        // PHP's Archivo_Local_Info creates a hash based on path, size, modtime.
        // For a stream, we might use filename and a processing timestamp for uniqueness if needed.
        // Let's use a hash of filename + parentId + current time to ensure a new record for each processing attempt of a stream.

        String uniqueKey = filename + "|" + parentId + "|" + System.currentTimeMillis(); // Make it unique per run
        String checksum = HashUtil.sha256(uniqueKey);

        FileInfo fileInfo = findByChecksum(checksum);
        if (fileInfo == null) {
            fileInfo = new FileInfo();
            fileInfo.setFilename(filename.length() > 255 ? filename.substring(0, 255) : filename);
            fileInfo.setParentId(parentId.intValue()); // Assuming parentId fits in Integer
            fileInfo.setType(type.length() > 64 ? type.substring(0, 64) : type);
            fileInfo.setDate(LocalDateTime.now()); // Processing time
            fileInfo.setChecksum(checksum);
            fileInfo.setSize(0); // Size unknown for stream without full read
            fileInfo.setReferenceId(0); // Not used in this context
            fileInfo.setDirectory(""); // Not applicable for stream

            entityManager.persist(fileInfo);
            log.info("Created new FileInfo record with ID: {} for file: {}", fileInfo.getId(), filename);
        }
        return fileInfo;
    }

    @Transactional(readOnly = true)
    public FileInfo findByChecksum(String checksum) {
        try {
            return entityManager.createQuery("SELECT fi FROM FileInfo fi WHERE fi.checksum = :checksum", FileInfo.class)
                    .setParameter("checksum", checksum)
                    .getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }
}
