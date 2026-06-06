package net.jojoaddison.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.jojoaddison.domain.Authority;
import net.jojoaddison.domain.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import tech.jhipster.config.JHipsterConstants;

@Component
@Profile({ JHipsterConstants.SPRING_PROFILE_DEVELOPMENT, JHipsterConstants.SPRING_PROFILE_TEST })
public class DevelopmentUsersInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevelopmentUsersInitializer.class);

    private final MongoTemplate template;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;
    private final Environment env;

    public DevelopmentUsersInitializer(
        MongoTemplate template,
        PasswordEncoder passwordEncoder,
        ObjectMapper objectMapper,
        ResourceLoader resourceLoader,
        Environment env
    ) {
        this.template = template;
        this.passwordEncoder = passwordEncoder;
        this.objectMapper = objectMapper;
        this.resourceLoader = resourceLoader;
        this.env = env;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Initializing development/test users from hc-admin-gw-data.json");
        try {
            DevelopmentUsersData developmentUsersData = objectMapper.readValue(
                resourceLoader.getResource("classpath:hc-admin-gw-data.json").getInputStream(),
                DevelopmentUsersData.class
            );

            if (env.acceptsProfiles(org.springframework.core.env.Profiles.of(JHipsterConstants.SPRING_PROFILE_DEVELOPMENT))) {
                developmentUsersData.dev().forEach(this::createUserFromJson);
            }

            if (env.acceptsProfiles(org.springframework.core.env.Profiles.of(JHipsterConstants.SPRING_PROFILE_TEST))) {
                developmentUsersData.test().forEach(this::createUserFromJson);
            }
        } catch (IOException e) {
            log.error("Failed to load development/test users from hc-admin-gw-data.json", e);
        }
    }

    private Authority ensureAuthority(String authorityName) {
        Authority authority = template.findById(authorityName, Authority.class);
        if (authority != null) {
            return authority;
        }

        Authority newAuthority = new Authority();
        newAuthority.setName(authorityName);
        return template.save(newAuthority);
    }

    private void createUserFromJson(DevelopmentUserJson developmentUserJson) {
        Query loginQuery = Query.query(Criteria.where("login").is(developmentUserJson.login()));
        if (template.exists(loginQuery, User.class)) {
            return;
        }

        User user = new User();
        user.setId(developmentUserJson.id());
        user.setLogin(developmentUserJson.login());
        user.setPassword(passwordEncoder.encode(developmentUserJson.login())); // Use login as default password
        user.setFirstName(developmentUserJson.firstName());
        user.setLastName(developmentUserJson.lastName());
        user.setEmail(developmentUserJson.email());
        user.setActivated(developmentUserJson.activated());
        user.setLangKey(Constants.DEFAULT_LANGUAGE);
        user.setCreatedBy(Constants.SYSTEM);
        user.setCreatedDate(Instant.now());

        Set<Authority> authorities = new HashSet<>();
        developmentUserJson.authorities().forEach(authorityName -> authorities.add(ensureAuthority(authorityName)));
        user.setAuthorities(authorities);

        template.save(user);
        log.info("Created development/test user '{}'", developmentUserJson.login());
    }

    private record DevelopmentUserJson(
        String id,
        String login,
        String email,
        String firstName,
        String lastName,
        Boolean activated,
        List<String> authorities
    ) {}

    private record DevelopmentUsersData(List<DevelopmentUserJson> dev, List<DevelopmentUserJson> test) {}
}
