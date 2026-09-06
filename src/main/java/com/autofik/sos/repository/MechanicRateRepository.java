package com.autofik.sos.repository;

import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.entity.MechanicRate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for the {@link MechanicRate} aggregate table (one row per mechanic). Used to look up
 * and upsert a mechanic's average rating / rating count whenever new feedback is submitted.
 */
@Repository
public interface MechanicRateRepository extends JpaRepository<MechanicRate, UUID> {
  /**
   * Use case : fetch the existing rating aggregate row for a mechanic
   *
   * @param mechanic the mechanic whose rating aggregate is being looked up
   * @return an Optional containing the MechanicRate row if one exists, or empty if this mechanic
   *     has no rating aggregate yet
   */
  Optional<MechanicRate> findByMechanic(Mechanic mechanic);
}
