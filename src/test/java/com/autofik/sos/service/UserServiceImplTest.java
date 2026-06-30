package com.autofik.sos.service;

import com.autofik.sos.domain.entity.User;
import com.autofik.sos.dto.request.CreateUserRequest;
import com.autofik.sos.dto.response.UserResponse;
import com.autofik.sos.mapper.UserMapper;
import com.autofik.sos.repository.UserRepository;
import com.autofik.sos.service.impl.UserServiceImpl;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
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
                .email("alice@example.com")
                .build();
        userResponse = new UserResponse(userId, "Alice", null, "alice@example.com", null, null);
    }

    @Test
    void create_setsAllFieldsFromRequest() {
        CreateUserRequest request = new CreateUserRequest("Alice", "0812345678", "alice@example.com");
        when(userRepository.save(any(User.class))).thenReturn(user);
        when(userMapper.toResponse(user)).thenReturn(userResponse);

        userService.create(request);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.getName()).isEqualTo("Alice");
        assertThat(saved.getPhone()).isEqualTo("0812345678");
        assertThat(saved.getEmail()).isEqualTo("alice@example.com");
    }

    @Test
    void create_returnsMapperResponse() {
        CreateUserRequest request = new CreateUserRequest("Alice", null, "alice@example.com");
        when(userRepository.save(any(User.class))).thenReturn(user);
        when(userMapper.toResponse(user)).thenReturn(userResponse);

        assertThat(userService.create(request)).isEqualTo(userResponse);
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

    @Test
    void getByEmail_found_returnsResponse() {
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        when(userMapper.toResponse(user)).thenReturn(userResponse);

        assertThat(userService.getByEmail("alice@example.com")).isEqualTo(userResponse);
    }

    @Test
    void getByEmail_notFound_throwsEntityNotFoundException() {
        when(userRepository.findByEmail("missing@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getByEmail("missing@example.com"))
                .isInstanceOf(EntityNotFoundException.class);
    }
}
