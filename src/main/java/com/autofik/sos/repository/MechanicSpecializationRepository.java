package com.autofik.sos.repository;

import com.autofik.sos.domain.entity.MechanicSpecialization;
import com.autofik.sos.domain.entity.MechanicSpecializationId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MechanicSpecializationRepository extends JpaRepository<MechanicSpecialization, MechanicSpecializationId> {

    List<MechanicSpecialization> findByMechanicMechanicId(UUID mechanicId);

    List<MechanicSpecialization> findBySpecializationSpcaId(UUID spcaId);
}
