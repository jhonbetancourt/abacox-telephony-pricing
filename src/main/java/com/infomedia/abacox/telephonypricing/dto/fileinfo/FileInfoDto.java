package com.infomedia.abacox.telephonypricing.dto.fileinfo;

import com.infomedia.abacox.telephonypricing.db.entity.FileInfo;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTO for {@link FileInfo}
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class FileInfoDto {
    private Long id;
    private String filename;
    private Integer parentId;
    private Integer size;
    private LocalDateTime date;
    private String checksum;
    private Integer referenceId;
    private String directory;
    private String type;
    private FileInfo.ProcessingStatus processingStatus;
}