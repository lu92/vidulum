package com.multi.vidulum.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserId {
    String id;

    public static UserId of(String id) {
        return new UserId(id);
    }
}
