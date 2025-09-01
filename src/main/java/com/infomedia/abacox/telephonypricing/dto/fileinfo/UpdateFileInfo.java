package com.infomedia.abacox.telephonypricing.dto.fileinfo;

import com.infomedia.abacox.telephonypricing.db.entity.FileInfo;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.openapitools.jackson.nullable.JsonNullable;

import java.time.LocalDateTime;

/**
 * DTO for updating {@link com.infomedia.abacox.telephonypricing.db.entity.FileInfo}
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class UpdateFileInfo {
    @NotBlank
    @Size(max = 255)
    private JsonNullable<String> filename = JsonNullable.undefined();

    @NotNull
    private JsonNullable<Integer> parentId = JsonNullable.undefined();

    @NotNull
    private JsonNullable<Integer> size = JsonNullable.undefined();

    private JsonNullable<LocalDateTime> date = JsonNullable.undefined();

    @NotBlank
    @Size(max = 64)
    private JsonNullable<String> checksum = JsonNullable.undefined();

    @NotNull
    private JsonNullable<Integer> referenceId = JsonNullable.undefined();

    @NotBlank
    @Size(max = 80)
    private JsonNullable<String> directory = JsonNullable.undefined();

    @NotBlank
    @Size(max = 64)
    private JsonNullable<String> type = JsonNullable.undefined();

    @NotNull
    private JsonNullable<FileInfo.ProcessingStatus> processingStatus = JsonNullable.undefined();
}