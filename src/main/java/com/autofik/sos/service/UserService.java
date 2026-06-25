package com.autofik.sos.service;

import com.autofik.sos.dto.request.CreateUserRequest;
import com.autofik.sos.dto.response.UserResponse;

import java.util.List;
import java.util.UUID;

public interface UserService {
    UserResponse create(CreateUserRequest request);
    UserResponse getById(UUID userId);
    UserResponse getByEmail(String email);
    List<UserResponse> getAll();
}
