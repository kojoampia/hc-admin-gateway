package net.jojoaddison.config;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import net.jojoaddison.domain.Authority;
import net.jojoaddison.domain.User;
import net.jojoaddison.security.AuthoritiesConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import tech.jhipster.config.JHipsterConstants;

@Component
@Profile(JHipsterConstants.SPRING_PROFILE_DEVELOPMENT)
public class DevelopmentUsersInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevelopmentUsersInitializer.class);

    private static final DevelopmentUser ADMIN_USER = new DevelopmentUser(
        "user-1",
        "admin",
        "admin",
        "admin@localhost",
        "Admin",
        "Administrator"
    );

    private static final DevelopmentUser STANDARD_USER = new DevelopmentUser("user-2", "user", "user", "user@localhost", "User", "User");

    private final MongoTemplate template;
    private final PasswordEncoder passwordEncoder;

    public DevelopmentUsersInitializer(MongoTemplate template, PasswordEncoder passwordEncoder) {
        this.template = template;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        Authority userAuthority = ensureAuthority(AuthoritiesConstants.USER);
        Authority adminAuthority = ensureAuthority(AuthoritiesConstants.ADMIN);

        createUserIfMissing(STANDARD_USER, Set.of(userAuthority));
        createUserIfMissing(ADMIN_USER, Set.of(adminAuthority, userAuthority));
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

    private void createUserIfMissing(DevelopmentUser developmentUser, Set<Authority> authorities) {
        Query loginQuery = Query.query(Criteria.where("login").is(developmentUser.login()));
        if (template.exists(loginQuery, User.class)) {
            return;
        }

        User user = new User();
        user.setId(developmentUser.id());
        user.setLogin(developmentUser.login());
        user.setPassword(passwordEncoder.encode(developmentUser.password()));
        user.setFirstName(developmentUser.firstName());
        user.setLastName(developmentUser.lastName());
        user.setEmail(developmentUser.email());
        user.setActivated(true);
        user.setLangKey(Constants.DEFAULT_LANGUAGE);
        user.setCreatedBy(Constants.SYSTEM);
        user.setCreatedDate(Instant.now());
        user.setAuthorities(new HashSet<>(authorities));

        template.save(user);
        log.info("Created development user '{}'", developmentUser.login());
    }

    private record DevelopmentUser(String id, String login, String password, String email, String firstName, String lastName) {}
}
