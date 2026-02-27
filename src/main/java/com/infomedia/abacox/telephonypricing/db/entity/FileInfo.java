package com.infomedia.abacox.telephonypricing.db.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnDefault;
import java.time.LocalDateTime;
import java.util.UUID;

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
    @SequenceGenerator(name = "file_info_id_seq", sequenceName = "file_info_id_seq", allocationSize = 1, initialValue = 10000000)
    @Column(name = "id", nullable = false)
    private Long id;

    /**
     * File name.
     * Original field: FILEINFO_ARCHIVO
     */
    @Column(name = "filename", length = 255, nullable = false)
    @ColumnDefault("''")
    private String filename;

    @Column(name = "plant_type_id", nullable = false)
    @ColumnDefault("0")
    private Integer plantTypeId;

    /**
     * Size of the file in bytes (uncompressed).
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
    @Column(name = "checksum", unique = true)
    private UUID checksum;

    @Column(name = "storage_bucket", length = 100)
    private String storageBucket;

    @Column(name = "storage_object_name", length = 100)
    private String storageObjectName;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false, length = 20)
    private ProcessingStatus processingStatus = ProcessingStatus.PENDING;

    public enum ProcessingStatus {
        PENDING,
        IN_PROGRESS,
        COMPLETED,
        FAILED,
        MISSING
    }
}