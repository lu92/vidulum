package com.multi.vidulum;

import com.multi.vidulum.security.token.Token;
import com.multi.vidulum.shared.DataCleaner;
import com.multi.vidulum.task.infrastructure.TaskEntity;
import com.multi.vidulum.user.infrastructure.UserEntity;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

@Component
public class CoreDataCleaner implements DataCleaner {

    /**
     * Empties the collections; it used to drop them.
     *
     * <p>Dropping takes the indexes with it. They are created at startup from the annotations, and
     * the cleaner runs just afterwards — so every index in this application lived for a fraction of
     * a second and then vanished, and the first write recreated the collection bare. The
     * uniqueness task F1 depends on survived the tests and did not survive the container.
     *
     * <p>Removing documents leaves the collection and its indexes in place, which is what "start
     * from clean data" was always supposed to mean.
     */
    @Override
    public void clean(MongoTemplate mongoTemplate) {
        // Security & User
        mongoTemplate.remove(new Query(), Token.class);
        mongoTemplate.remove(new Query(), UserEntity.class);

        // Other
        mongoTemplate.remove(new Query(), TaskEntity.class);
    }
}
