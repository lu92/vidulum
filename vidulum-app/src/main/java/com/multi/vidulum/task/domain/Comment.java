package com.multi.vidulum.task.domain;

import java.time.ZonedDateTime;

public record Comment(
        String message,
        ZonedDateTime created) {
}
