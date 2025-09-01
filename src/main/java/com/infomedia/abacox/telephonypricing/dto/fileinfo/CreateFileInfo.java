package com.infomedia.abacox.telephonypricing.dto.fileinfo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTO for creating {@link com.infomedia.abacox.telephonypricing.db.entity.FileInfo}
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CreateFileInfo {
    @NotBlank
    @Size(max = 255)
    private String filename;

    @NotNull
    private Integer parentId;

    @NotNull
    private Integer size;

    private LocalDateTime date;

    @NotBlank
    @Size(max = 64)
    private String checksum;

    @NotNull
    private Integer referenceId;

    @NotBlank
    @Size(max = 80)
    private String directory;

    @NotBlank
    @Size(max = 64)
    private String type;
}