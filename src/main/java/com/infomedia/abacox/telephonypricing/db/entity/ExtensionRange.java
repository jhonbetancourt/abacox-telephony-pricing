// src/main/java/com/infomedia/abacox/telephonypricing/db/entity/ExtensionRange.java
package com.infomedia.abacox.telephonypricing.db.entity;

import com.infomedia.abacox.telephonypricing.db.entity.superclass.ActivableEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnDefault;

@Entity
@Table(
    name = "extension_range",
    indexes = {
        // Used in EmployeeLookupService::getExtensionRanges
        @Index(name = "idx_ext_range_comm_loc_active", columnList = "comm_location_id, active"),
        
        // Used for range comparison logic
        @Index(name = "idx_ext_range_vals", columnList = "range_start, range_end")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class ExtensionRange extends ActivableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "extension_range_id_seq")
    @SequenceGenerator(
            name = "extension_range_id_seq",
            sequenceName = "extension_range_id_seq",
            allocationSize = 1,
            initialValue = 10000000
    )
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "comm_location_id")
    private Long commLocationId;

    @ManyToOne
    @JoinColumn(
            name = "comm_location_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_extension_range_comm_location")
    )
    private CommunicationLocation commLocation;

    @Column(name = "subdivision_id")
    private Long subdivisionId;

    @ManyToOne
    @JoinColumn(
            name = "subdivision_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_extension_range_subdivision")
    )
    private Subdivision subdivision;

    @Column(name = "prefix", length = 250, nullable = false)
    @ColumnDefault("''")
    private String prefix;

    @Column(name = "range_start", length = 50, nullable = false)
    @ColumnDefault("0")
    private Long rangeStart;

    @Column(name = "range_end", length = 50, nullable = false)
    @ColumnDefault("0")
    private Long rangeEnd;

    @Column(name = "cost_center_id")
    private Long costCenterId;

    @ManyToOne
    @JoinColumn(
            name = "cost_center_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_extension_range_cost_center")
    )
    private CostCenter costCenter;
}