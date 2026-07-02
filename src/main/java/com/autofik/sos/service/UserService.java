package com.autofik.sos.service;

import com.autofik.sos.dto.request.CreateUserRequest;
import com.autofik.sos.dto.response.UserResponse;

import java.util.List;
import java.util.UUID;

/**
 * Service Interface defining the business use cases for managing driver User accounts.
 * Handles profile creation and lookups. Authentication is delegated to the SSO team.
 */
public interface UserService {

    /**
     * Use case: Register a new driver account.
     * @param request Contains name, phone, and email.
     * @return The created user profile.
     */
    UserResponse create(CreateUserRequest request);

    /**
     * Use case: Fetch a user profile by their unique internal ID.
     * @param userId Identifies the target user.
     * @return The matching user profile.
     */
    UserResponse getById(UUID userId);

    /**
     * Use case: Look up a user by their email address (e.g., during SSO token validation).
     * @param email The email to search for.
     * @return The matching user profile.
     */
    UserResponse getByEmail(String email);

    /**
     * Use case: Admin dashboard — retrieve all registered user accounts.
     * @return Full list of all user records.
     */
    List<UserResponse> getAll();
}
