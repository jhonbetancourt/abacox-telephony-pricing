package com.infomedia.abacox.telephonypricing.db.entity.superclass;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.*;
import lombok.experimental.SuperBuilder;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder(toBuilder = true)
@MappedSuperclass
public class ActivableEntity extends AuditedEntity{

    @Builder.Default
    @Column(name = "active", nullable = false, columnDefinition = "boolean default true")
    private boolean active = true;
}
