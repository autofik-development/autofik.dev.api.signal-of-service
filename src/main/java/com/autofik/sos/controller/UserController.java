package com.autofik.sos.controller;

import com.autofik.sos.dto.response.UserResponse;
import com.autofik.sos.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

// READ-ONLY. User profiles are created and managed by the SSO team.
// This service only reads users to validate SOS requests.
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping
    public List<UserResponse> getAll() {
        return userService.getAll();
    }

    @GetMapping("/{userId}")
    public UserResponse getById(@PathVariable UUID userId) {
        return userService.getById(userId);
    }
}
