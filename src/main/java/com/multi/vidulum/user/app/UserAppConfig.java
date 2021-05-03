package com.multi.vidulum.user.app;

import com.multi.vidulum.user.domain.UserFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class UserAppConfig {

    @Bean
    public UserFactory userFactory() {
        return new UserFactory();
    }
}
