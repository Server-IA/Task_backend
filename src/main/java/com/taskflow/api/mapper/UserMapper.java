package com.taskflow.api.mapper;

import com.taskflow.api.dto.response.UserResponse;
import com.taskflow.api.dto.response.UserSummaryResponse;
import com.taskflow.api.entity.User;
import org.mapstruct.Mapper;

@Mapper
public interface UserMapper {

    UserResponse toResponse(User user);

    UserSummaryResponse toSummary(User user);
}
