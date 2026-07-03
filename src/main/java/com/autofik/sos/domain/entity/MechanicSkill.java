package com.autofik.sos.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "mechanic_skill")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
@ToString
public class MechanicSkill {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mechanic_profile_id", nullable = false)
    @ToString.Exclude
    private Mechanic mechanic;

    // Postgres TEXT[] — allows multiple tags per skill entry (e.g. ["tire", "brake"]).
    // Searched via unnest() in MechanicRepository.findByH3CellsAndSkillTag.
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "skill_tags", columnDefinition = "TEXT[]")
    private String[] skillTags;

    // Vehicle origin group the mechanic specializes in (e.g. "Asia", "Europe", "Americas").
    @Column(name = "vehicle_specialization")
    private String vehicleSpecialization;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
