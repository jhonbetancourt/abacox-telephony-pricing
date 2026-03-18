package com.infomedia.abacox.telephonypricing.component.ftp;

import lombok.extern.log4j.Log4j2;
import org.apache.ftpserver.ftplet.DefaultFtplet;
import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.ftplet.FtpRequest;
import org.apache.ftpserver.ftplet.FtpSession;
import org.apache.ftpserver.ftplet.FtpletResult;

import java.util.Set;

/**
 * Restricts FTP users to upload-only access.
 * Listing is allowed so FTP clients can connect normally.
 * Blocked: download (RETR), delete (DELE), rename (RNFR/RNTO), remove dir (RMD).
 */
@Log4j2
public class UploadOnlyFtplet extends DefaultFtplet {

    // Blocked commands — everything not listed here is allowed by default
    private static final Set<String> BLOCKED_COMMANDS = Set.of(
            "RETR",        // download file
            "DELE",        // delete file
            "RNFR", "RNTO", // rename
            "RMD",         // remove directory
            "SITE"         // site-specific commands
    );

    @Override
    public FtpletResult beforeCommand(FtpSession session, FtpRequest request)
            throws FtpException {

        String command = request.getCommand().toUpperCase();

        if (BLOCKED_COMMANDS.contains(command)) {
            log.debug("FTP command '{}' blocked for user '{}'", command,
                    session.getUser() != null ? session.getUser().getName() : "unknown");
            session.write(
                    new org.apache.ftpserver.ftplet.DefaultFtpReply(
                            550, "Permission denied: operation not allowed."));
            return FtpletResult.SKIP;
        }

        return FtpletResult.DEFAULT;
    }
}
