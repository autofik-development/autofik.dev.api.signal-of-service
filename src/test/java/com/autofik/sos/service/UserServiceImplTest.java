package com.autofik.sos.service;

import com.autofik.sos.domain.entity.User;
import com.autofik.sos.domain.enums.UserStatus;
import com.autofik.sos.dto.response.UserResponse;
import com.autofik.sos.mapper.UserMapper;
import com.autofik.sos.repository.UserRepository;
import com.autofik.sos.service.impl.UserServiceImpl;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock UserRepository userRepository;
    @Mock UserMapper userMapper;

    @InjectMocks UserServiceImpl userService;

    private final UUID userId = UUID.randomUUID();
    private User user;
    private UserResponse userResponse;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .userId(userId)
                .name("Alice")
                .phone("0812345678")
                .build();
        userResponse = new UserResponse(userId, "Alice", "0812345678", UserStatus.ACTIVE, null);
    }

    @Test
    void getById_existingUser_returnsResponse() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userMapper.toResponse(user)).thenReturn(userResponse);

        assertThat(userService.getById(userId)).isEqualTo(userResponse);
    }

    @Test
    void getById_notFound_throwsEntityNotFoundException() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getById(userId))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining(userId.toString());
    }
}
