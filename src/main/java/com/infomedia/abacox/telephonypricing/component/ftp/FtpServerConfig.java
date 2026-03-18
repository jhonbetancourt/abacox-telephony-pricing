package com.infomedia.abacox.telephonypricing.component.ftp;

import jakarta.annotation.PreDestroy;
import lombok.extern.log4j.Log4j2;
import org.apache.ftpserver.FtpServer;
import org.apache.ftpserver.FtpServerFactory;
import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.listener.ListenerFactory;
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory;
import org.apache.ftpserver.usermanager.SaltedPasswordEncryptor;
import org.apache.ftpserver.usermanager.impl.BaseUser;
import org.apache.ftpserver.usermanager.impl.WritePermission;
import org.apache.ftpserver.ftplet.UserManager;
import org.apache.ftpserver.DataConnectionConfigurationFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.net.URL;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Configuration
@Log4j2
public class FtpServerConfig {

    @Value("${app.ftp.enabled:true}")
    private boolean ftpEnabled;

    @Value("${app.ftp.port:2121}")
    private int ftpPort;

    @Value("${app.ftp.passive-ports:30000-30010}")
    private String passivePorts;

    @Value("${app.ftp.root-dir:./ftp-root}")
    private String ftpRootDir;

    @Value("${app.ftp.users:ftpuser:changeme}")
    private String ftpUsers;

    // External IP/hostname advertised in PASV responses.
    // Required when running behind Docker/NAT so clients connect to the host, not the container IP.
    // Leave empty for local/direct connections.
    @Value("${app.ftp.passive-address:}")
    private String passiveAddress;

    // Used to derive passive address when app.ftp.passive-address is not set explicitly.
    @Value("${abacox.base-url:}")
    private String abacoxBaseUrl;

    private FtpServer ftpServer;

    @PostConstruct
    public void startFtpServer() {
        if (!ftpEnabled) {
            log.info("FTP server is disabled (app.ftp.enabled=false). Skipping startup.");
            return;
        }

        try {
            // Ensure root directory exists
            File rootDir = new File(ftpRootDir);
            if (!rootDir.exists() && !rootDir.mkdirs()) {
                log.error("Failed to create FTP root directory: {}", ftpRootDir);
                return;
            }

            // Configure listener
            ListenerFactory listenerFactory = new ListenerFactory();
            listenerFactory.setPort(ftpPort);

            DataConnectionConfigurationFactory dataConnFactory = new DataConnectionConfigurationFactory();
            dataConnFactory.setPassivePorts(passivePorts);
            String resolvedPassiveAddress = resolvePassiveAddress();
            if (resolvedPassiveAddress != null) {
                dataConnFactory.setPassiveExternalAddress(resolvedPassiveAddress);
                log.info("FTP passive external address set to: {}", resolvedPassiveAddress);
            }
            listenerFactory.setDataConnectionConfiguration(dataConnFactory.createDataConnectionConfiguration());

            // Configure user manager
            PropertiesUserManagerFactory userManagerFactory = new PropertiesUserManagerFactory();
            userManagerFactory.setPasswordEncryptor(new SaltedPasswordEncryptor());
            UserManager userManager = userManagerFactory.createUserManager();

            // Create users from "user1:pass1,user2:pass2" config
            for (String userEntry : ftpUsers.split(",")) {
                String[] parts = userEntry.trim().split(":", 2);
                if (parts.length != 2) {
                    log.warn("Invalid FTP user entry (expected user:pass): {}", userEntry);
                    continue;
                }
                BaseUser user = new BaseUser();
                user.setName(parts[0].trim());
                user.setPassword(parts[1].trim());
                user.setHomeDirectory(ftpRootDir);
                user.setEnabled(true);
                user.setAuthorities(Collections.singletonList(new WritePermission()));
                userManager.save(user);
                log.info("Registered FTP user '{}' with home: {}", user.getName(), ftpRootDir);
            }

            // Register upload-only ftplet to block downloads, listings, deletes, renames
            Map<String, org.apache.ftpserver.ftplet.Ftplet> ftplets = new LinkedHashMap<>();
            ftplets.put("uploadOnly", new UploadOnlyFtplet());

            // Build and start server
            FtpServerFactory serverFactory = new FtpServerFactory();
            serverFactory.addListener("default", listenerFactory.createListener());
            serverFactory.setUserManager(userManager);
            serverFactory.setFtplets(ftplets);

            ftpServer = serverFactory.createServer();
            ftpServer.start();
            log.info("Embedded FTP server started on port {} (passive: {}) [upload-only mode]",
                    ftpPort, passivePorts);

        } catch (FtpException e) {
            log.error("Failed to start embedded FTP server", e);
        }
    }

    private String resolvePassiveAddress() {
        if (passiveAddress != null && !passiveAddress.isBlank()) {
            return passiveAddress;
        }
        if (abacoxBaseUrl != null && !abacoxBaseUrl.isBlank()) {
            try {
                return new URL(abacoxBaseUrl).getHost();
            } catch (Exception e) {
                log.warn("Could not parse host from ABACOX_BASE_URL '{}': {}", abacoxBaseUrl, e.getMessage());
            }
        }
        return null;
    }

    @PreDestroy
    public void stopFtpServer() {
        if (ftpServer != null && !ftpServer.isStopped()) {
            ftpServer.stop();
            log.info("Embedded FTP server stopped.");
        }
    }
}
