package com.multi.vidulum.common;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserId {
    String id;

    public static UserId of(String id) {
        return new UserId(id);
    }
}
