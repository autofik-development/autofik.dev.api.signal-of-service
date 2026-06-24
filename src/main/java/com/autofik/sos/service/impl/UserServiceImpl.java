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

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;

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

    @Override
    @Transactional(readOnly = true)
    public UserResponse getById(UUID userId) {
        return userMapper.toResponse(userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found: " + userId)));
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponse getByEmail(String email) {
        return userMapper.toResponse(userRepository.findByEmail(email)
                .orElseThrow(() -> new EntityNotFoundException("User not found with email: " + email)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserResponse> getAll() {
        return userMapper.toResponseList(userRepository.findAll());
    }
}
