package com.multi.vidulum;

import com.multi.vidulum.security.token.Token;
import com.multi.vidulum.shared.DataCleaner;
import com.multi.vidulum.task.infrastructure.TaskEntity;
import com.multi.vidulum.user.infrastructure.UserEntity;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

@Component
public class CoreDataCleaner implements DataCleaner {

    @Override
    public void clean(MongoTemplate mongoTemplate) {
        // Security & User
        mongoTemplate.dropCollection(Token.class);
        mongoTemplate.dropCollection(UserEntity.class);

        // Other
        mongoTemplate.dropCollection(TaskEntity.class);
    }
}
