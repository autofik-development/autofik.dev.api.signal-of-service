package com.autofik.sos.domain.entity;

import lombok.*;

import java.io.Serializable;
import java.util.UUID;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode
public class MechanicSpecializationId implements Serializable {

    private UUID mechanic;
    private UUID specialization;
}