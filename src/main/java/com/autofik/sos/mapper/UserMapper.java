package com.autofik.sos.mapper;

import com.autofik.sos.domain.entity.User;
import com.autofik.sos.dto.response.UserResponse;
import org.mapstruct.Mapper;

import java.util.List;

@Mapper(componentModel = "spring")
public interface UserMapper {

    UserResponse toResponse(User user);

    List<UserResponse> toResponseList(List<User> users);
}
