package com.autofik.sos.repository;

import com.autofik.sos.domain.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    /**
     * Use case : fetch a user by their phone number (e.g. for OTP login lookups)
     * @param phone the user's phone number
     * @return an Optional containing the User if found, or empty if no user exists with that phone number
     */
    Optional<User> findByPhone(String phone);
}
