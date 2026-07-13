package com.autofik.sos.repository;

import com.autofik.sos.domain.entity.Mechanic;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MechanicRepository extends JpaRepository<Mechanic, UUID> {
    /**
     * Use case : fetch a mechanic by their email address
     * @param email the mechanic's email addres
     * @return an Optional containing the Mechanic if found, or empty if no mechanic exists with that email
     */
    Optional<Mechanic> findByEmail(String email);

    /**
     * Use case : fetch all mechanics matching a given availability status (e.g. all currently available mechanics for dispatch)
     * @param availableStatus true to fetch available mechanics, false to fetch unavailable ones
     * @return list of mechanics matching the given availability status
     */
    List<Mechanic> findByAvailableStatus(Boolean availableStatus);

    /**
     * GEOSPATIAL SEARCH - STAGE 1: Coarse Grid Filtering (Fast)
     * Instant B-tree scan on H3 cell IDs — eliminates 99% of mechanics before PostGIS touches them.
     */
    @Query("SELECT m FROM Mechanic m WHERE m.currentH3Index IN :cellIds AND m.availableStatus = true")
    List<Mechanic> findByH3Cells(@Param("cellIds") List<String> cellIds);

    /**
     * GEOSPATIAL SEARCH - STAGE 2: Precise Radius Calculation (Accurate)
     * PostGIS ST_DWithin exact distance check on the Stage 1 shortlist.
     * service_radius_km is multiplied by 1000 to convert to meters for ST_DWithin.
     */
    @Query(value = """
            SELECT * FROM mechanic m
            WHERE m.id IN :ids
              AND ST_DWithin(
                    m.current_location,
                    ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
                    m.service_radius_km * 1000
                  )
            """, nativeQuery = true)
    List<Mechanic> filterByRadius(
            @Param("ids") List<UUID> ids,
            @Param("lat") double lat,
            @Param("lng") double lng);

    /**
     * Batch-fetch mechanics by ID with skills eagerly loaded in a single JOIN FETCH query.
     * Use this instead of findAllById when the skill list will be accessed (e.g. problemType filter),
     * to avoid an N+1 lazy-load per mechanic.
     */
    @Query("SELECT DISTINCT m FROM Mechanic m LEFT JOIN FETCH m.skills WHERE m.mechanicId IN :ids AND m.availableStatus = true")
    List<Mechanic> findByIdsWithSkills(@Param("ids") List<UUID> ids);

    /**
     * Stage 2 with user broadcast radius cap.
     * Uses LEAST() so both the user's broadcast limit and the mechanic's own service radius are respected.
     */
    @Query(value = """
            SELECT * FROM mechanic m
            WHERE m.id IN :ids
              AND ST_DWithin(
                    m.current_location,
                    ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
                    LEAST(m.service_radius_km * 1000, :broadcastRadiusM)
                  )
            """, nativeQuery = true)
    List<Mechanic> filterByRadiusAndBroadcast(
            @Param("ids") List<UUID> ids,
            @Param("lat") double lat,
            @Param("lng") double lng,
            @Param("broadcastRadiusM") double broadcastRadiusM);

    /**
     * H3 Grid Filter + Mechanic Skill Match combined.
     * Uses unnest() to search inside the skill_tags TEXT[] array.
     */
    @Query(value = """
            SELECT DISTINCT m.* FROM mechanic m
            JOIN mechanic_skill ms ON ms.mechanic_profile_id = m.id
            WHERE m.current_h3_index IN :cellIds
              AND m.available_status = true
              AND EXISTS (
                  SELECT 1 FROM unnest(ms.skill_tags) tag
                  WHERE UPPER(tag) = :problemType
              )
            """, nativeQuery = true)
    List<Mechanic> findByH3CellsAndSkillTag(
            @Param("cellIds") List<String> cellIds,
            @Param("problemType") String problemType);
}
