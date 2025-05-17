package com.infomedia.abacox.telephonypricing.entity;

import jakarta.persistence.*;
import lombok.*;

import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnDefault;
import java.time.LocalDateTime;

/**
 * Entity representing file information/metadata.
 * Original table name: fileinfo
 */
@Entity
@Table(name = "file_info")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class FileInfo {

    /**
     * Primary key for the file information.
     * Original field: FILEINFO_ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "file_info_id_seq")
    @SequenceGenerator(
            name = "file_info_id_seq",
            sequenceName = "file_info_id_seq",
            allocationSize = 1,
            initialValue = 10000000
    )
    @Column(name = "id", nullable = false)
    private Long id;

    /**
     * File name.
     * Original field: FILEINFO_ARCHIVO
     */
    @Column(name = "filename", length = 255, nullable = false)
    @ColumnDefault("")
    private String filename;

    /**
     * ID of the entity that this file belongs to.
     * Original field: FILEINFO_PERTENECE
     */
    @Column(name = "parent_id", nullable = false)
    @ColumnDefault("0")
    private Integer parentId;

    /**
     * Size of the file in bytes.
     * Original field: FILEINFO_TAMANO
     */
    @Column(name = "size", nullable = false)
    @ColumnDefault("0")
    private Integer size;

    /**
     * Date associated with the file.
     * Original field: FILEINFO_FECHA
     */
    @Column(name = "date")
    private LocalDateTime date;

    /**
     * Control or checksum information.
     * Original field: FILEINFO_CTL
     */
    @Column(name = "checksum", length = 64, nullable = false)
    @ColumnDefault("")
    private String checksum;

    /**
     * Reference ID, possibly linking to another entity.
     * Original field: FILEINFO_REF_ID
     */
    @Column(name = "reference_id", nullable = false)
    @ColumnDefault("0")
    private Integer referenceId;

    /**
     * Directory where the file is stored.
     * Original field: FILEINFO_DIRECTORIO
     */
    @Column(name = "directory", length = 80, nullable = false)
    @ColumnDefault("")
    private String directory;

    /**
     * Type or MIME type of the file.
     * Original field: FILEINFO_TIPO
     */
    @Column(name = "type", length = 64, nullable = false)
    @ColumnDefault("")
    private String type;
}