package com.multi.vidulum;

import com.multi.vidulum.security.auth.AuthenticationService;
import com.multi.vidulum.security.auth.RegisterRequest;
import com.multi.vidulum.shared.DataCleaner;
import com.multi.vidulum.shared.cqrs.CqrsGatewayConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Clock;
import java.util.List;


@SpringBootApplication
@Import(CqrsGatewayConfiguration.class)
public class VidulumApplication {

    @Autowired
    private MongoTemplate mongoTemplate;

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    public static void main(String[] args) {
        SpringApplication.run(VidulumApplication.class, args);
    }

    @Bean
    public CommandLineRunner commandLineRunner(
            AuthenticationService service,
            List<DataCleaner> dataCleaners
    ) {
        dataCleaners.forEach(cleaner -> cleaner.clean(mongoTemplate));
        return args -> {
            var admin = RegisterRequest.builder()
                    .username("Admin")
                    .email("admin@mail.com")
                    .password("password12")
                    .build();
            System.out.println("Admin token: " + service.register(admin).getAccessToken());

            var manager = RegisterRequest.builder()
                    .username("Manager")
                    .email("manager@mail.com")
                    .password("password12")
                    .build();
            System.out.println("Manager token: " + service.register(manager).getAccessToken());
        };
    }
}
