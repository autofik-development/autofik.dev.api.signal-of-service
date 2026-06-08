package com.autofik.sos.domain.entity;

import com.autofik.sos.domain.enums.ExperienceLevel;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "mechanic_specialization")
@IdClass(MechanicSpecializationId.class)
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
public class MechanicSpecialization {

    @Id
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mechanic_id", nullable = false)
    private Mechanic mechanic;

    @Id
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "spca_id", nullable = false)
    private Specialization specialization;

    @Column(name = "year_of_experience")
    private Integer yearOfExperience;

    @Enumerated(EnumType.STRING)
    @Column(name = "experience_level")
    private ExperienceLevel experienceLevel;
}
