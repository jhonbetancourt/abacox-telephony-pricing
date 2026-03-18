package com.infomedia.abacox.telephonypricing.component.ftp;

import com.infomedia.abacox.telephonypricing.component.cdrprocessing.FileInfoPersistenceService;
import com.infomedia.abacox.telephonypricing.db.entity.CdrLoadControl;
import com.infomedia.abacox.telephonypricing.db.repository.CdrLoadControlRepository;
import com.infomedia.abacox.telephonypricing.multitenancy.MultitenantRunner;
import com.infomedia.abacox.telephonypricing.multitenancy.TenantInitializer;
import com.infomedia.abacox.telephonypricing.service.MinioStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.List;

@Component
@Log4j2
@RequiredArgsConstructor
public class FtpCdrPollingWorker implements TenantInitializer {

    private static final long STABILITY_THRESHOLD_MS = 2000;

    private final CdrLoadControlRepository cdrLoadControlRepository;
    private final FileInfoPersistenceService fileInfoPersistenceService;
    private final MultitenantRunner multitenantRunner;
    private final MinioStorageService minioStorageService;

    @Value("${app.ftp.enabled:true}")
    private boolean ftpEnabled;

    @Value("${app.ftp.root-dir:./ftp-root}")
    private String ftpRootDir;

    @Override
    public void onTenantInit(String tenantId) {
        if (!ftpEnabled) {
            return;
        }
        List<CdrLoadControl> entries = cdrLoadControlRepository.findByActiveTrue();
        for (CdrLoadControl entry : entries) {
            File folder = new File(ftpRootDir, entry.getName());
            if (!folder.exists() && folder.mkdirs()) {
                log.info("Created FTP folder on startup for tenant '{}': {}", tenantId, folder.getAbsolutePath());
            }
        }
    }

    @Scheduled(fixedDelayString = "${app.ftp.poll-interval-ms:30000}")
    public void pollFtpDirectories() {
        if (!ftpEnabled) {
            return;
        }
        if (!minioStorageService.isReady()) {
            log.trace("Skipping FTP poll cycle: MinIO is unavailable.");
            multitenantRunner.runForAllTenants(this::ensureFoldersExist);
            return;
        }
        multitenantRunner.runForAllTenants(this::pollForCurrentTenant);
    }

    private void ensureFoldersExist() {
        List<CdrLoadControl> entries = cdrLoadControlRepository.findByActiveTrue();
        for (CdrLoadControl entry : entries) {
            File folder = new File(ftpRootDir, entry.getName());
            if (!folder.exists() && folder.mkdirs()) {
                log.debug("Created FTP folder: {}", folder.getAbsolutePath());
            }
        }
    }

    private void pollForCurrentTenant() {
        List<CdrLoadControl> entries = cdrLoadControlRepository.findByActiveTrue();

        for (CdrLoadControl entry : entries) {
            File folder = new File(ftpRootDir, entry.getName());

            if (!folder.exists()) {
                if (folder.mkdirs()) {
                    log.debug("Created FTP folder: {}", folder.getAbsolutePath());
                }
                continue;
            }

            File[] files = folder.listFiles(f -> f.isFile());
            if (files == null || files.length == 0) {
                continue;
            }

            for (File file : files) {
                if (!isStable(file)) {
                    log.debug("Skipping unstable file (still being written?): {}", file.getName());
                    continue;
                }
                processFile(file, entry);
            }
        }
    }

    private void processFile(File file, CdrLoadControl entry) {
        String filename = file.getName();
        Long plantTypeId = entry.getPlantTypeId().longValue();
        log.info("FTP poller picked up file '{}' for plantTypeId={}", filename, plantTypeId);

        try {
            fileInfoPersistenceService.createOrGetFileInfo(filename, plantTypeId, file);
            // Delete regardless of whether this is a new or duplicate file
            if (!file.delete()) {
                log.warn("Could not delete FTP file after processing: {}", file.getAbsolutePath());
            } else {
                log.info("FTP file '{}' queued and deleted successfully.", filename);
            }
        } catch (IOException e) {
            log.error("IO error processing FTP file '{}'. Will retry next poll cycle.", filename, e);
        } catch (Exception e) {
            log.error("Unexpected error processing FTP file '{}'. Will retry next poll cycle.", filename, e);
        }
    }

    private boolean isStable(File file) {
        long lastModified = file.lastModified();
        return lastModified > 0 && (System.currentTimeMillis() - lastModified) >= STABILITY_THRESHOLD_MS;
    }
}
