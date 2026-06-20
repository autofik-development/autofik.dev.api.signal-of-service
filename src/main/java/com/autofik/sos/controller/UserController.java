package com.autofik.sos.controller;

import com.autofik.sos.dto.request.CreateUserRequest;
import com.autofik.sos.dto.response.UserResponse;
import com.autofik.sos.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse create(@Valid @RequestBody CreateUserRequest request) {
        return userService.create(request);
    }

    @GetMapping("/{userId}")
    public UserResponse getById(@PathVariable UUID userId) {
        return userService.getById(userId);
    }

    @GetMapping("/email")
    public UserResponse getByEmail(@RequestParam String email) {
        return userService.getByEmail(email);
    }
}
