package com.autofik.sos.repository;

import com.autofik.sos.domain.entity.MechanicSkill;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MechanicSkillRepository extends JpaRepository<MechanicSkill, UUID> {

    /**
     * All skill entries belonging to one mechanic.
     */
    List<MechanicSkill> findByMechanicMechanicId(UUID mechanicId);

    /**
     * Ownership-scoped lookup: resolves a skill only when it belongs to the given mechanic, so a
     * guessed or leaked skill id cannot be used to edit or delete another mechanic's entry.
     */
    Optional<MechanicSkill> findByIdAndMechanicMechanicId(UUID id, UUID mechanicId);
}
