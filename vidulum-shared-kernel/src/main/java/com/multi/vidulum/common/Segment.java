package com.multi.vidulum.common;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class Segment {
    private String name;

    public static Segment of(String name) {
        return new Segment(name);
    }

    public static Segment unknown() {
        return new Segment("unknown");
    }
}
