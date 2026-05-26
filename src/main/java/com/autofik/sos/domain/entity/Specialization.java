package com.autofik.sos.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "specialization")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class Specialization {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "spca_id", updatable = false, nullable = false)
    private UUID spcaId;

    @Column(unique = true, nullable = false)
    private String name;

    // Relations
    @OneToMany(mappedBy = "specialization", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<MechanicSpecialization> mechanicSpecializations;
}

