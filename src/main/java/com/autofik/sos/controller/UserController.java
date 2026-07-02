package com.autofik.sos.controller;

import com.autofik.sos.dto.request.CreateUserRequest;
import com.autofik.sos.dto.response.UserResponse;
import com.autofik.sos.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST Controller for driver user account management.
 * Base URL: /api/users
 * Handles profile creation and lookups. Login/authentication is handled by the SSO service.
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    // POST /api/users — Register a new driver account
    // Returns 201 CREATED with the created user profile
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse create(@Valid @RequestBody CreateUserRequest request) {
        return userService.create(request);
    }

    // GET /api/users — Admin: list all registered user accounts
    @GetMapping
    public List<UserResponse> getAll() {
        return userService.getAll();
    }

    // GET /api/users/{userId} — Fetch a user profile by their internal UUID
    @GetMapping("/{userId}")
    public UserResponse getById(@PathVariable UUID userId) {
        return userService.getById(userId);
    }

    // GET /api/users/email?email= — Look up a user by email address
    // Used when resolving identity from an SSO JWT token claim
    @GetMapping("/email")
    public UserResponse getByEmail(@RequestParam String email) {
        return userService.getByEmail(email);
    }
}
