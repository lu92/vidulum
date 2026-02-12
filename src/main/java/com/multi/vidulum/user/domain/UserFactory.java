package com.multi.vidulum.user.domain;

import com.multi.vidulum.common.UserId;

import java.util.LinkedList;

public class UserFactory {

    public User createUser(UserId userId, String username, String password, String email) {
        return User.builder()
                .userId(userId)
                .username(username)
                .password(password)
                .email(email)
                .isActive(false)
                .portfolios(new LinkedList<>())
                .build();
    }
}
