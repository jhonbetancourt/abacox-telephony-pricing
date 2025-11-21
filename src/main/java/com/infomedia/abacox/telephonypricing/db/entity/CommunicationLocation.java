// src/main/java/com/infomedia/abacox/telephonypricing/db/entity/CommunicationLocation.java
package com.infomedia.abacox.telephonypricing.db.entity;

import com.infomedia.abacox.telephonypricing.db.entity.superclass.ActivableEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnDefault;

@Entity
@Table(
    name = "communication_location",
    indexes = {
        // Critical for findActiveCommLocationsByPlantType
        @Index(name = "idx_comm_loc_plant_active", columnList = "plant_type_id, active"),
        
        // Critical for general lookups in reports
        @Index(name = "idx_comm_loc_indicator", columnList = "indicator_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class CommunicationLocation extends ActivableEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "communication_location_id_seq")
    @SequenceGenerator(
            name = "communication_location_id_seq",
            sequenceName = "communication_location_id_seq",
            allocationSize = 1,
            initialValue = 10000000
    )
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "directory", length = 80, nullable = false)
    @ColumnDefault("''")
    private String directory;

    @Column(name = "plant_type_id")
    private Long plantTypeId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "plant_type_id", 
            insertable = false, 
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_communication_location_plant_type")
    )
    private PlantType plantType;

    @Column(name = "indicator_id")
    private Long indicatorId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "indicator_id", 
            insertable = false, 
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_communication_location_indicator")
    )
    private Indicator indicator;

    @Column(name = "pbx_prefix", length = 32, nullable = false)
    @ColumnDefault("''")
    private String pbxPrefix;

    @Column(name = "serial", length = 200, nullable = false)
    @ColumnDefault("''")
    private String serial;
}