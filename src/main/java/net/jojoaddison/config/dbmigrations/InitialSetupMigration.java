package net.jojoaddison.config.dbmigrations;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.jojoaddison.config.Constants;
import net.jojoaddison.domain.Authority;
import net.jojoaddison.domain.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import tech.jhipster.config.JHipsterConstants;

/**
 * Seeds local development and test accounts from {@code hc-admin-gw-data.json}.
 *
 * <p>That file is the single source of truth for these users: ids, logins, emails, passwords and
 * authorities all come from it. The ids in particular are a cross-service contract —
 * {@code hc-admin-service}'s seed data references the admin and operator ids as {@code managedBy}
 * and {@code createdBy} values, so they must not drift.
 *
 * <p>Seeding is <strong>additive</strong>: a user is created only when no user with that login
 * exists, so accounts created through the API survive restarts. Nothing here is ever dropped, and
 * passwords are never logged.
 *
 * <p>This runs under {@code dev} and {@code test} only. Production bootstrapping is handled
 * separately by {@link net.jojoaddison.config.AdminBootstrapInitializer}.
 */
@Component
@Profile({ JHipsterConstants.SPRING_PROFILE_DEVELOPMENT, JHipsterConstants.SPRING_PROFILE_TEST })
public class InitialSetupMigration implements ApplicationRunner {

    static final String SEED_DATA_LOCATION = "hc-admin-gw-data.json";

    private static final Logger logger = LoggerFactory.getLogger(InitialSetupMigration.class);

    private final MongoTemplate template;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;
    private final Environment environment;

    public InitialSetupMigration(
        MongoTemplate template,
        PasswordEncoder passwordEncoder,
        ObjectMapper objectMapper,
        Environment environment
    ) {
        this.template = template;
        this.passwordEncoder = passwordEncoder;
        this.objectMapper = objectMapper;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        SeedUsers seedUsers;
        try (InputStream inputStream = new ClassPathResource(SEED_DATA_LOCATION).getInputStream()) {
            seedUsers = objectMapper.readValue(inputStream, SeedUsers.class);
        } catch (IOException e) {
            // Seeding is a local convenience: log loudly but let the application start.
            logger.error("Failed to read seed users from {}", SEED_DATA_LOCATION, e);
            return;
        }

        if (environment.acceptsProfiles(Profiles.of(JHipsterConstants.SPRING_PROFILE_DEVELOPMENT))) {
            seedUsers.getDev().forEach(this::createUserIfMissing);
        }
        if (environment.acceptsProfiles(Profiles.of(JHipsterConstants.SPRING_PROFILE_TEST))) {
            seedUsers.getTest().forEach(this::createUserIfMissing);
        }
        logger.info("Initial setup migration completed successfully");
    }

    private void createUserIfMissing(SeedUser seedUser) {
        if (template.exists(Query.query(Criteria.where("login").is(seedUser.getLogin())), User.class)) {
            return;
        }

        User user = new User();
        user.setId(seedUser.getId());
        user.setLogin(seedUser.getLogin());
        user.setPassword(passwordEncoder.encode(seedUser.resolvePassword()));
        user.setFirstName(seedUser.getFirstName());
        user.setLastName(seedUser.getLastName());
        user.setEmail(seedUser.getEmail());
        user.setActivated(Boolean.TRUE.equals(seedUser.getActivated()));
        user.setLangKey(Constants.DEFAULT_LANGUAGE);
        user.setCreatedBy(Constants.SYSTEM);
        user.setCreatedDate(Instant.now());

        Set<Authority> authorities = new HashSet<>();
        seedUser.getAuthorities().forEach(name -> authorities.add(ensureAuthority(name)));
        user.setAuthorities(authorities);

        template.save(user);
        // Never log the password.
        logger.info("Created seed user '{}'", seedUser.getLogin());
    }

    private Authority ensureAuthority(String authorityName) {
        Authority existing = template.findById(authorityName, Authority.class);
        if (existing != null) {
            return existing;
        }
        Authority authority = new Authority();
        authority.setName(authorityName);
        return template.save(authority);
    }

    /** Root of {@code hc-admin-gw-data.json}: one list of users per profile. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class SeedUsers {

        private List<SeedUser> dev = new ArrayList<>();
        private List<SeedUser> test = new ArrayList<>();

        public List<SeedUser> getDev() {
            return dev;
        }

        public void setDev(List<SeedUser> dev) {
            this.dev = dev == null ? new ArrayList<>() : dev;
        }

        public List<SeedUser> getTest() {
            return test;
        }

        public void setTest(List<SeedUser> test) {
            this.test = test == null ? new ArrayList<>() : test;
        }
    }

    /** A single seed account. Must stay {@code static} so Jackson can instantiate it. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class SeedUser {

        private String id;
        private String login;
        private String email;
        private String firstName;
        private String lastName;
        private String password;
        private Boolean activated;
        private List<String> authorities = new ArrayList<>();

        /**
         * Falls back to the login when no password is declared. The {@code test} fixtures
         * ({@code deactivated}, {@code noauth}, {@code malformed}) exist to exercise edge cases and
         * carry no password of their own.
         */
        String resolvePassword() {
            return password == null || password.isBlank() ? login : password;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getLogin() {
            return login;
        }

        public void setLogin(String login) {
            this.login = login;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public String getFirstName() {
            return firstName;
        }

        public void setFirstName(String firstName) {
            this.firstName = firstName;
        }

        public String getLastName() {
            return lastName;
        }

        public void setLastName(String lastName) {
            this.lastName = lastName;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public Boolean getActivated() {
            return activated;
        }

        public void setActivated(Boolean activated) {
            this.activated = activated;
        }

        public List<String> getAuthorities() {
            return authorities;
        }

        public void setAuthorities(List<String> authorities) {
            this.authorities = authorities == null ? new ArrayList<>() : authorities;
        }
    }
}
