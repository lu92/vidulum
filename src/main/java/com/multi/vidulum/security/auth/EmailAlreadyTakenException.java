package com.multi.vidulum.security.auth;

public class EmailAlreadyTakenException extends RuntimeException {

    public EmailAlreadyTakenException(String email) {
        super("Email '%s' is already registered".formatted(email));
    }
}
