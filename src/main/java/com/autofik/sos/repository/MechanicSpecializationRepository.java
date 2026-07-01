package com.autofik.sos.repository;

import com.autofik.sos.domain.entity.MechanicSpecialization;
import com.autofik.sos.domain.entity.MechanicSpecializationId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;
/**
 * Data Access Object (DAO) interface for the link table between Mechanics and Specializations.
 * It uses a composite primary key class (MechanicSpecializationId) instead of a simple UUID.
 */
@Repository
public interface MechanicSpecializationRepository extends JpaRepository<MechanicSpecialization, MechanicSpecializationId> {

    // list mechanic skill belong to specified mechanic
    List<MechanicSpecialization> findByMechanicMechanicId(UUID mechanicId);
    // list machanic links to that specified skill
    List<MechanicSpecialization> findBySpecializationSpcaId(UUID spcaId);
}
