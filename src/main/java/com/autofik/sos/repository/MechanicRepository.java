package com.autofik.sos.repository;

import com.autofik.sos.domain.entity.Mechanic;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface MechanicRepository extends JpaRepository<Mechanic, UUID> {

  /**
   * Mechanic.rate is an inverse-side @OneToOne, which Hibernate cannot proxy — it issues a separate
   * SELECT per row unless the association is fetched up front. Without this graph a 20-row page
   * costs 21 queries. The graph forces a single LEFT JOIN instead.
   */
  @Override
  @EntityGraph(attributePaths = "rate")
  Page<Mechanic> findAll(Pageable pageable);

  /**
   * Use case : fetch a mechanic by their email address
   *
   * @param email the mechanic's email addres
   * @return an Optional containing the Mechanic if found, or empty if no mechanic exists with that
   *     email
   */
  Optional<Mechanic> findByEmail(String email);

  /**
   * Use case : fetch all mechanics matching a given availability status (e.g. all currently
   * available mechanics for dispatch)
   *
   * @param availableStatus true to fetch available mechanics, false to fetch unavailable ones
   * @return list of mechanics matching the given availability status
   */
  List<Mechanic> findByAvailableStatus(Boolean availableStatus);

  /**
   * GEOSPATIAL SEARCH - STAGE 1: Coarse Grid Filtering (Fast) Instant B-tree scan on H3 cell IDs —
   * eliminates 99% of mechanics before PostGIS touches them.
   */
  @EntityGraph(attributePaths = "rate")
  @Query("SELECT m FROM Mechanic m WHERE m.currentH3Index IN :cellIds AND m.availableStatus = true")
  List<Mechanic> findByH3Cells(@Param("cellIds") List<String> cellIds);

  // The three native geo filters below return ids, never entities. A native query cannot carry the
  // "rate" entity graph, so every Mechanic it hydrated pulled its inverse @OneToOne rating with a
  // SELECT of its own: 5,532 statements for one SOS create against the load-test seed. Callers
  // filter by id, and load the few entities they really need through findAllWithRateById.

  /**
   * GEOSPATIAL SEARCH - STAGE 2: Precise Radius Calculation (Accurate) PostGIS ST_DWithin exact
   * distance check on the Stage 1 shortlist. service_radius_km is multiplied by 1000 to convert to
   * meters for ST_DWithin.
   */
  @Query(
      value =
          """
            SELECT m.id FROM mechanic m
            WHERE m.id IN :ids
              AND ST_DWithin(
                    m.current_location,
                    ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
                    m.service_radius_km * 1000
                  )
            """,
      nativeQuery = true)
  List<UUID> filterByRadius(
      @Param("ids") List<UUID> ids, @Param("lat") double lat, @Param("lng") double lng);

  /**
   * Batch-fetch mechanics by ID with skills eagerly loaded in a single JOIN FETCH query. Use this
   * instead of findAllById when the skill list will be accessed (e.g. problemType filter), to avoid
   * an N+1 lazy-load per mechanic.
   */
  @Query(
      "SELECT DISTINCT m FROM Mechanic m LEFT JOIN FETCH m.skills WHERE m.mechanicId IN :ids AND m.availableStatus = true")
  List<Mechanic> findByIdsWithSkills(@Param("ids") List<UUID> ids);

  /**
   * Stage 2 with user broadcast radius cap. Uses LEAST() so both the user's broadcast limit and the
   * mechanic's own service radius are respected.
   */
  @Query(
      value =
          """
            SELECT m.id FROM mechanic m
            WHERE m.id IN :ids
              AND ST_DWithin(
                    m.current_location,
                    ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
                    LEAST(m.service_radius_km * 1000, :broadcastRadiusM)
                  )
            """,
      nativeQuery = true)
  List<UUID> filterByRadiusAndBroadcast(
      @Param("ids") List<UUID> ids,
      @Param("lat") double lat,
      @Param("lng") double lng,
      @Param("broadcastRadiusM") double broadcastRadiusM);

  /**
   * H3 Grid Filter + Mechanic Skill Match combined. Uses unnest() to search inside the skill_tags
   * TEXT[] array. Also drops a mechanic who is already on a live job (an ACCEPTED bid on a ticket
   * that has not finished) and one whose last location ping is older than {@code seenSince}. A
   * mechanic with no recorded ping time is kept.
   */
  @Query(
      value =
          """
            SELECT DISTINCT m.id FROM mechanic m
            JOIN mechanic_skill ms ON ms.mechanic_profile_id = m.id
            WHERE m.current_h3_index IN :cellIds
              AND m.available_status = true
              AND (m.location_updated_at IS NULL OR m.location_updated_at >= :seenSince)
              AND NOT EXISTS (
                  SELECT 1 FROM bid_sos b
                  JOIN sos_request s ON s.sos_id = b.sos_id
                  WHERE b.mechanic_id = m.id
                    AND b.status = 'ACCEPTED'
                    AND s.status IN ('ACCEPTED', 'ON_THE_WAY', 'ARRIVED', 'IN_PROGRESS')
              )
              AND EXISTS (
                  SELECT 1 FROM unnest(ms.skill_tags) tag
                  WHERE UPPER(tag) = :problemType
              )
            """,
      nativeQuery = true)
  List<UUID> findByH3CellsAndSkillTag(
      @Param("cellIds") List<String> cellIds,
      @Param("problemType") String problemType,
      @Param("seenSince") java.time.LocalDateTime seenSince);

  /**
   * Holds the mechanic's row lock until the calling transaction ends, so two accepts of the same
   * mechanic on different tickets run one after the other. NO KEY UPDATE excludes other lockers and
   * row updates but not the FOR KEY SHARE a bid insert takes through its foreign key. Empty when no
   * such row exists.
   */
  @Query(
      value = "SELECT id FROM mechanic WHERE id = :mechanicId FOR NO KEY UPDATE",
      nativeQuery = true)
  Optional<UUID> lockById(@Param("mechanicId") UUID mechanicId);

  /**
   * One statement for a persisted location ping. location_moved_at moves only when the fix is at
   * least {@code movedM} metres from the stored one (or there was none), the rule the service used
   * to apply in Java; SET expressions read the old row, so the CASE sees the previous position.
   * Returns the rows changed: 0 means no such mechanic. {@code @Transactional} here, not on the
   * caller, so a throttled ping that writes nothing never borrows a connection.
   *
   * <p>ponytail: compares ping to ping, so GPS jitter can look like movement (the mechanic is not
   * released) and a crawl under the threshold per ping can look parked. Compare against an anchor
   * point instead if that turns out to matter.
   */
  @Transactional
  @Modifying
  @Query(
      value =
          """
            UPDATE mechanic SET
              location_moved_at = CASE
                  WHEN current_location IS NULL
                    OR ST_Distance(current_location,
                         ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography) >= :movedM
                  THEN :now ELSE location_moved_at END,
              current_location = ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
              current_h3_index = :h3,
              location_updated_at = :now
            WHERE id = :id
            """,
      nativeQuery = true)
  int updateLocation(
      @Param("id") UUID id,
      @Param("lat") double lat,
      @Param("lng") double lng,
      @Param("h3") String h3,
      @Param("movedM") double movedM,
      @Param("now") LocalDateTime now);

  /** Mechanics by id with their rating joined in, so mapping them costs one query, not N + 1. */
  @EntityGraph(attributePaths = "rate")
  @Query("SELECT m FROM Mechanic m WHERE m.mechanicId IN :ids")
  List<Mechanic> findAllWithRateById(@Param("ids") List<UUID> ids);
}
