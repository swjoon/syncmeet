package com.backend.backend.dto;

import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class UserDto {

    private Long userCode;
    private String userName;
    private String oauthType;
    private String oauthSubject;
    private OffsetDateTime regDate;
}
