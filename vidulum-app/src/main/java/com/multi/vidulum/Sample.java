package com.multi.vidulum;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Getter
@Builder
@ToString
@Document("sample")
public class Sample {

    @Id
    private String id;
    private String text;
}
