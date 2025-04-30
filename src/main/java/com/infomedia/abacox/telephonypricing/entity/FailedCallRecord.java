package com.infomedia.abacox.telephonypricing.entity;

import com.infomedia.abacox.telephonypricing.entity.superclass.AuditedEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

@Entity
@Table(name = "failed_call_record")
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
            allocationSize = 1
    )
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "comm_location_id")
    private Long commLocationId; // Reference to the source location

    @Column(name = "employee_extension", length = 50)
    private String employeeExtension;

    @Column(name = "cdr_string", columnDefinition = "TEXT")
    private String cdrString; // The raw CDR line that failed

    @Column(name = "error_type", length = 50)
    private String errorType; // e.g., PARSING_ERROR, ENRICHMENT_ERROR, DB_ERROR

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage; // Detailed error message

    @Column(name = "original_call_record_id")
    private Long originalCallRecordId; // If failure happened during reprocessing/update

    @Column(name = "file_info_id")
    private Long fileInfoId; // Reference to the source file info if available

    @Column(name = "processing_step", length = 100)
    private String processingStep; // e.g., "Parsing", "LookupEmployee", "CalculateRate"
}