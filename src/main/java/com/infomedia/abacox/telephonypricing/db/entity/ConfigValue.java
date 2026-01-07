package com.infomedia.abacox.telephonypricing.db.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
@Entity
@Table(name = "config_value")
public class ConfigValue {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "key", nullable = false, length = 100, unique = true)
    private String key;

    @Column(name = "value", nullable = false, length = 1024)
    private String value;

    @Column(name = "config_group", length = 100)
    private String group;
}
