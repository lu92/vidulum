package com.multi.vidulum.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * MongoDB document for storing sequence counters.
 * Used by BusinessIdGenerator to generate unique sequential IDs across multiple instances.
 */
@Document(collection = "sequences")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SequenceDocument {

    @Id
    private String id;

    private long value;
}
