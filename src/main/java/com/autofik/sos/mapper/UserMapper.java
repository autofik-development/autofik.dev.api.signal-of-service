package com.autofik.sos.mapper;

import com.autofik.sos.domain.entity.User;
import com.autofik.sos.dto.response.UserResponse;
import java.util.List;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface UserMapper {

  UserResponse toResponse(User user);

  List<UserResponse> toResponseList(List<User> users);
}
