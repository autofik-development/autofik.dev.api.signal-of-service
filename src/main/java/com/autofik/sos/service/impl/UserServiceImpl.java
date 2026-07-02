package com.autofik.sos.service.impl;

import com.autofik.sos.domain.entity.User;
import com.autofik.sos.dto.request.CreateUserRequest;
import com.autofik.sos.dto.response.UserResponse;
import com.autofik.sos.mapper.UserMapper;
import com.autofik.sos.repository.UserRepository;
import com.autofik.sos.service.UserService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service          // Registers this class as a Spring-managed service bean
@RequiredArgsConstructor  // Lombok: generates a constructor injecting all final dependencies
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final UserMapper userMapper; // MapStruct mapper: converts between User entity and UserResponse DTO

    /**
     * WORKFLOW: Registers a new driver account with name, phone, and email.
     */
    @Override
    @Transactional
    public UserResponse create(CreateUserRequest request) {
        User user = User.builder()
                .name(request.name())
                .phone(request.phone())
                .email(request.email())
                .build();
        return userMapper.toResponse(userRepository.save(user));
    }

    /**
     * WORKFLOW: Fetches a user profile by their internal UUID.
     */
    @Override
    @Transactional(readOnly = true) // read-only hint: no writes so JPA skips dirty-checking overhead
    public UserResponse getById(UUID userId) {
        return userMapper.toResponse(userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found: " + userId)));
    }

    /**
     * WORKFLOW: Looks up a user by email — used when resolving identity from SSO token claims.
     */
    @Override
    @Transactional(readOnly = true)
    public UserResponse getByEmail(String email) {
        return userMapper.toResponse(userRepository.findByEmail(email)
                .orElseThrow(() -> new EntityNotFoundException("User not found with email: " + email)));
    }

    /**
     * WORKFLOW: Returns all registered user accounts — intended for admin use only.
     */
    @Override
    @Transactional(readOnly = true)
    public List<UserResponse> getAll() {
        return userMapper.toResponseList(userRepository.findAll());
    }
}
