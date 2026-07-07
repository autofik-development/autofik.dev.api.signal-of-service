package com.autofik.sos.service;

import com.autofik.sos.dto.response.UserResponse;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * Service for read operations on {@link com.autofik.sos.domain.entity.User}.
 * Exposes user data as {@link UserResponse} DTOs for API consumption.
 */
public interface UserService {

    /**
     * Use case : fetch a single user's details by their ID
     * @param userId the ID of the user to fetch
     * @return the user's details as a UserResponse
     */
    UserResponse getById(UUID userId);

    /**
     * Use case : fetch all users in the system
     * @return list of all users as UserResponse
     */
    Page<UserResponse> getAll(Pageable pageable);
}
