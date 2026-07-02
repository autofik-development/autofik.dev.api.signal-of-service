package com.autofik.sos.repository;

import com.autofik.sos.domain.entity.SosMedia;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;
/**
 * Data Access Object (DAO) interface for managing SosMedia records.
 * Handles database operations for photos/videos uploaded during an emergency request.
 */
@Repository // mark this as a database repository component managed by spring
public interface SosMediaRepository extends JpaRepository<SosMedia, UUID> {
    // list all media files which associated with a specific sos request
    List<SosMedia> findBySosRequestSosId(UUID sosId);
}
