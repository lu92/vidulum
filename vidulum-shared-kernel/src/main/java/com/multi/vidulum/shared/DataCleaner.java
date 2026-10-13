package com.multi.vidulum.shared;

import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Clears one module's MongoDB collections at startup. Every module registers its own; the
 * application collects them as {@code List<DataCleaner>} and knows nothing about what each clears.
 *
 * <p><b>Empty the collections, do not drop them.</b> Dropping takes the indexes with it. They are
 * created at startup from the annotations and the cleaners run just afterwards — so every index in
 * this application lived for a fraction of a second and then vanished, and the first write
 * recreated the collection bare. The uniqueness that task F1 depends on survived the tests and did
 * not survive the container.
 *
 * <p>{@code mongoTemplate.remove(new Query(), X.class)} leaves the collection and its indexes in
 * place, which is what "start from clean data" was always supposed to mean.
 */
public interface DataCleaner {
    void clean(MongoTemplate mongoTemplate);
}
