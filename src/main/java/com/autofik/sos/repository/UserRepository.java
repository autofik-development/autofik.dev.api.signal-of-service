package com.autofik.sos.repository;

import com.autofik.sos.domain.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;
/**
 * Data Access Object (DAO) interface for managing User profiles.
 * Used primarily for system authentication, login flows, and account verifications.
 */
@Repository
public interface UserRepository extends JpaRepository<User, UUID> {
    // find user by email
    Optional<User> findByEmail(String email);
    // find user by phone
    Optional<User> findByPhone(String phone);
}
