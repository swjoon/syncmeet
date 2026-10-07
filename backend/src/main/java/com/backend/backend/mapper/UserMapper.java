package com.backend.backend.mapper;

import com.backend.backend.dto.UserDto;

import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserMapper {

    Optional<UserDto> findByOauth(
            @Param("oauthType") String oauthType,
            @Param("oauthSubject") String oauthSubject
    );

    int insertIfAbsent(
            @Param("userName") String userName,
            @Param("oauthType") String oauthType,
            @Param("oauthSubject") String oauthSubject
    );
}
