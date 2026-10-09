package com.autofik.sos.repository;

import com.autofik.sos.domain.entity.SosOffer;
import java.util.Collection;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface SosOfferRepository extends JpaRepository<SosOffer, UUID> {

  /** Records that these mechanics were offered the ticket; an existing row is left as it is. */
  @Transactional
  @Modifying
  @Query(
      value =
          "INSERT INTO sos_offer (id, sos_id, mechanic_id, offered_at) "
              + "SELECT gen_random_uuid(), :sosId, m, now() FROM unnest(CAST(:mechanicIds AS uuid[])) AS m "
              + "ON CONFLICT (sos_id, mechanic_id) DO NOTHING",
      nativeQuery = true)
  int recordOffered(@Param("sosId") UUID sosId, @Param("mechanicIds") UUID[] mechanicIds);

  /**
   * Marks the mechanic's first bid on the ticket. Inserts the row when none exists (an offer that
   * reached them through the reconnect sync, which is a read and records nothing).
   */
  @Transactional
  @Modifying
  @Query(
      value =
          "INSERT INTO sos_offer (id, sos_id, mechanic_id, offered_at, bid_at) "
              + "VALUES (gen_random_uuid(), :sosId, :mechanicId, now(), now()) "
              + "ON CONFLICT (sos_id, mechanic_id) "
              + "DO UPDATE SET bid_at = COALESCE(sos_offer.bid_at, now())",
      nativeQuery = true)
  int recordBid(@Param("sosId") UUID sosId, @Param("mechanicId") UUID mechanicId);

  default void recordOffered(UUID sosId, Collection<UUID> mechanicIds) {
    recordOffered(sosId, mechanicIds.toArray(UUID[]::new));
  }
}
