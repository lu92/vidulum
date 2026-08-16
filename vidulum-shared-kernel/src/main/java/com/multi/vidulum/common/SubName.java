package com.multi.vidulum.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class SubName {
    private String name;

    public static SubName of(String subName) {
        return new SubName(subName);
    }

    public static SubName none() {
        return new SubName("");
    }
}
