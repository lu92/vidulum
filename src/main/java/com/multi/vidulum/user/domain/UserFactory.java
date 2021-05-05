package com.multi.vidulum.user.domain;

import java.util.LinkedList;

public class UserFactory {

    public User createUser(String username, String password, String email) {
        return User.builder()
                .username(username)
                .password(password)
                .email(email)
                .isActive(false)
                .portfolios(new LinkedList<>())
                .build();
    }
}
