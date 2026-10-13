package com.autofik.sos.repository;

import com.autofik.sos.domain.entity.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

  /**
   * Use case : fetch a user by their phone number (e.g. for OTP login lookups)
   *
   * @param phone the user's phone number
   * @return an Optional containing the User if found, or empty if no user exists with that phone
   *     number
   */
  Optional<User> findByPhone(String phone);

  /**
   * Holds the customer's row lock until the calling transaction ends, so create and rebroadcast for
   * the same customer run one after the other: a customer may hold only one live ticket, and a new
   * ticket has no row of its own to lock yet. NO KEY UPDATE leaves the FOR KEY SHARE that a ticket
   * or vehicle insert takes through its foreign key unblocked. Empty when no such row exists.
   */
  @Query(
      value = "SELECT user_id FROM users WHERE user_id = :userId FOR NO KEY UPDATE",
      nativeQuery = true)
  Optional<UUID> lockById(@Param("userId") UUID userId);
}
