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

    // search by email
    Optional<Mechanic> findByEmail(String email);

    // check do the email have already registered
    boolean existsByEmail(String email);

    // list all mechanic filter by status
    List<Mechanic> findByIsAvailable(Boolean isAvailable);

    /**
     * =========================================================================
     * GEOSPATIAL SEARCH - STAGE 1: Coarse Grid Filtering (Fast)
     * =========================================================================
     * Uber's H3 maps the world into a grid of hexagonal cells.
     * This query performs a super-fast, standard database scan on a list of cell ID strings.
     * It instantly eliminates 99% of mechanics who are too far away.
     */
    @Query("SELECT m FROM Mechanic m WHERE m.currentH3Index IN :cellIds AND m.isAvailable = true")
    List<Mechanic> findByH3Cells(@Param("cellIds") List<String> cellIds);

    /**
     * =========================================================================
     * GEOSPATIAL SEARCH - STAGE 2: Precise Radius Calculation (Accurate)
     * =========================================================================
     * This uses PostGIS (a spatial extension for PostgreSQL) via a 'nativeQuery = true' (raw SQL).
     * It takes the short-listed mechanic IDs from Stage 1 and calculates their EXACT distance
     * to the user's current GPS location.
     * * - ST_MakePoint(:lng, :lat): Creates a GPS point coordinate.
     * - ST_SetSRID(..., 4326): Tells the database to use the standard global GPS coordinate system (WGS 84).
     * - ST_DWithin(..., m.service_radius_m): Checks if the distance between the mechanic and user is less than or equal to the mechanic's max driving radius.
     */
    @Query(value = """
            SELECT * FROM mechanic m
            WHERE m.mechanic_id IN :ids
              AND ST_DWithin(
                    m.current_location,
                    ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
                    m.service_radius_m
                  )
            """, nativeQuery = true)
    List<Mechanic> filterByRadius(
            @Param("ids") List<UUID> ids,
            @Param("lat") double lat,
            @Param("lng") double lng);

    /**
     * Advanced H3 Grid Filter + Mechanic Skill Match combined.
     * Finds available mechanics who are not only nearby in the H3 cell grid, but who ALSO
     * possess the exact skill needed (e.g., matching "Flat Tire" or "Engine Overheat" to their profile).
     * * - JOIN m.specializations: Joins the bridging table.
     * - UPPER(...) = UPPER(...): Makes the problem type search case-insensitive (e.g., "tire" matches "TIRE").
     */
    @Query("""
            SELECT DISTINCT m FROM Mechanic m
            JOIN m.specializations ms
            JOIN ms.specialization s
            WHERE m.currentH3Index IN :cellIds
              AND m.isAvailable = true
              AND UPPER(s.name) = UPPER(:problemType)
            """)
    List<Mechanic> findByH3CellsAndSpecialization(
            @Param("cellIds") List<String> cellIds,
            @Param("problemType") String problemType);
}
