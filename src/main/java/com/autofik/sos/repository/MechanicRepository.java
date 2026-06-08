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

    Optional<Mechanic> findByEmail(String email);

    boolean existsByEmail(String email);

    List<Mechanic> findByIsAvailable(Boolean isAvailable);

    // Stage 1 — H3 coarse filter: plain B-tree index scan on current_h3_index
    @Query("SELECT m FROM Mechanic m WHERE m.currentH3Index IN :cellIds AND m.isAvailable = true")
    List<Mechanic> findByH3Cells(@Param("cellIds") List<String> cellIds);

    // Stage 2 — PostGIS exact radius check on the Stage 1 shortlist only
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
}
