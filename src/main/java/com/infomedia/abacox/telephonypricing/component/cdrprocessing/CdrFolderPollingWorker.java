package com.infomedia.abacox.telephonypricing.component.cdrprocessing;


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
public class CdrFolderPollingWorker implements TenantInitializer {

    private static final long STABILITY_THRESHOLD_MS = 2000;

    private final CdrLoadControlRepository cdrLoadControlRepository;
    private final FileInfoPersistenceService fileInfoPersistenceService;
    private final MultitenantRunner multitenantRunner;
    private final MinioStorageService minioStorageService;

    @Value("${app.cdr.folder.enabled:true}")
    private boolean cdrFolderEnabled;

    @Value("${app.cdr.folder.root-dir:/app/data/cdr-root}")
    private String cdrFolderRootDir;

    @Value("${app.cdr.folder.ignored-extensions:bin}")
    private String ignoredExtensionsConfig;

    @Override
    public void onTenantInit(String tenantId) {
        if (!cdrFolderEnabled) {
            return;
        }
        List<CdrLoadControl> entries = cdrLoadControlRepository.findByActiveTrue();
        for (CdrLoadControl entry : entries) {
            File folder = new File(cdrFolderRootDir, entry.getName());
            if (!folder.exists() && folder.mkdirs()) {
                log.info("Created CDR folder on startup for tenant '{}': {}", tenantId, folder.getAbsolutePath());
            }
        }
    }

    @Scheduled(fixedDelayString = "${app.cdr.folder.poll-interval-ms:30000}")
    public void pollCdrDirectories() {
        if (!cdrFolderEnabled) {
            return;
        }
        if (!minioStorageService.isReady()) {
            log.trace("Skipping CDR folder poll cycle: MinIO is unavailable.");
            multitenantRunner.runForAllTenants(this::ensureFoldersExist);
            return;
        }
        multitenantRunner.runForAllTenants(this::pollForCurrentTenant);
    }

    private void ensureFoldersExist() {
        List<CdrLoadControl> entries = cdrLoadControlRepository.findByActiveTrue();
        for (CdrLoadControl entry : entries) {
            File folder = new File(cdrFolderRootDir, entry.getName());
            if (!folder.exists() && folder.mkdirs()) {
                log.debug("Created CDR folder: {}", folder.getAbsolutePath());
            }
        }
    }

    private void pollForCurrentTenant() {
        List<CdrLoadControl> entries = cdrLoadControlRepository.findByActiveTrue();

        for (CdrLoadControl entry : entries) {
            File folder = new File(cdrFolderRootDir, entry.getName());

            if (!folder.exists()) {
                if (folder.mkdirs()) {
                    log.debug("Created CDR folder: {}", folder.getAbsolutePath());
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

                if (isIgnoredExtension(file)) {
                    log.info("Ignoring file with restricted extension: {}", file.getName());
                    moveToInvalidFolder(file, folder);
                    continue;
                }

                processFile(file, entry);
            }
        }
    }

    private boolean isIgnoredExtension(File file) {
        if (ignoredExtensionsConfig == null || ignoredExtensionsConfig.isBlank()) {
            return false;
        }
        String fileName = file.getName();
        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex == -1) {
            return false;
        }
        String extension = fileName.substring(lastDotIndex + 1).toLowerCase();
        for (String ignored : ignoredExtensionsConfig.split(",")) {
            if (extension.equals(ignored.trim().toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private void moveToInvalidFolder(File file, File tenantFolder) {
        File invalidFolder = new File(tenantFolder, "invalid");
        if (!invalidFolder.exists() && !invalidFolder.mkdirs()) {
            log.error("Could not create 'invalid' folder: {}", invalidFolder.getAbsolutePath());
            return;
        }
        File targetFile = new File(invalidFolder, file.getName());
        // Handle name collision
        if (targetFile.exists()) {
            targetFile = new File(invalidFolder, System.currentTimeMillis() + "_" + file.getName());
        }
        if (file.renameTo(targetFile)) {
            log.info("Moved ignored file '{}' to '{}'", file.getName(), targetFile.getAbsolutePath());
        } else {
            log.warn("Could not move ignored file '{}' to '{}'", file.getName(), targetFile.getAbsolutePath());
        }
    }

    private void processFile(File file, CdrLoadControl entry) {
        String filename = file.getName();
        Long plantTypeId = entry.getPlantTypeId().longValue();
        log.info("CDR folder poller picked up file '{}' for plantTypeId={}", filename, plantTypeId);

        try {
            fileInfoPersistenceService.createOrGetFileInfo(filename, plantTypeId, file);
            // Delete regardless of whether this is a new or duplicate file
            if (!file.delete()) {
                log.warn("Could not delete CDR file after processing: {}", file.getAbsolutePath());
            } else {
                log.info("CDR file '{}' queued and deleted successfully.", filename);
            }
        } catch (IOException e) {
            log.error("IO error processing CDR file '{}'. Will retry next poll cycle.", filename, e);
        } catch (Exception e) {
            log.error("Unexpected error processing CDR file '{}'. Will retry next poll cycle.", filename, e);
        }
    }

    private boolean isStable(File file) {
        long lastModified = file.lastModified();
        return lastModified > 0 && (System.currentTimeMillis() - lastModified) >= STABILITY_THRESHOLD_MS;
    }
}
