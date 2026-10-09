package com.autofik.sos.repository;

import com.autofik.sos.domain.entity.MechanicSkill;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MechanicSkillRepository extends JpaRepository<MechanicSkill, UUID> {

  /** All skill entries belonging to one mechanic. */
  List<MechanicSkill> findByMechanicMechanicId(UUID mechanicId);
}
