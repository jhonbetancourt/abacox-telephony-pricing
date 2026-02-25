package com.infomedia.abacox.telephonypricing.db.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

@Entity
@Table(name = "history_control")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class HistoryControl {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "ref_table", nullable = false)
    private Integer refTable;

    @Column(name = "ref_id")
    private Long refId;

    @Column(name = "history_since")
    private LocalDateTime historySince;
}
