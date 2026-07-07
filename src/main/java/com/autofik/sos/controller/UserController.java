package com.autofik.sos.controller;

import com.autofik.sos.dto.response.UserResponse;
import com.autofik.sos.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

// READ-ONLY. User profiles are created and managed by the SSO team.
// This service only reads users to validate SOS requests.
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping
    public Page<UserResponse> getAll(@PageableDefault(size = 20) Pageable pageable) {
        return userService.getAll(pageable);
    }

    @GetMapping("/{userId}")
    public UserResponse getById(@PathVariable UUID userId) {
        return userService.getById(userId);
    }
}
