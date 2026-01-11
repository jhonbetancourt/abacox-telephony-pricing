package com.infomedia.abacox.telephonypricing.db.entity;

import com.infomedia.abacox.telephonypricing.db.entity.superclass.AuditedEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

@Entity
@Table(
    name = "failed_call_record",
    indexes = {
        // Optimizes ProcessingFailureReportQueries
        @Index(name = "idx_failed_call_created", columnList = "created_date"),
        @Index(name = "idx_failed_call_error", columnList = "error_type"),
        @Index(name = "idx_failed_call_comm_loc", columnList = "comm_location_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class FailedCallRecord extends AuditedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "failed_call_record_id_seq")
    @SequenceGenerator(
            name = "failed_call_record_id_seq",
            sequenceName = "failed_call_record_id_seq",
            allocationSize = 1,
            initialValue = 1000000000
    )
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "employee_extension", length = 50)
    private String employeeExtension;

    @Column(name = "error_type", length = 50)
    private String errorType;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "original_call_record_id")
    private Long originalCallRecordId;

    @Column(name = "processing_step", length = 100)
    private String processingStep;

    @Column(name = "file_info_id")
    private Long fileInfoId;

    @ManyToOne
    @JoinColumn(
            name = "file_info_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_call_record_file_info")
    )
    private FileInfo fileInfo;

    @Column(name = "comm_location_id")
    private Long commLocationId;

    @ManyToOne
    @JoinColumn(
            name = "comm_location_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_call_record_comm_location")
    )
    private CommunicationLocation commLocation;

    @ToString.Exclude
    @Column(name = "ctl_hash", unique = true)
    private UUID ctlHash;
}