package net.jojoaddison.config;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import net.jojoaddison.domain.Authority;
import net.jojoaddison.domain.User;
import net.jojoaddison.security.AuthoritiesConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates the first administrator on an empty database, in any profile.
 *
 * <p>A gateway deployed against a fresh production database would otherwise have no account to log
 * in with — {@link net.jojoaddison.config.dbmigrations.InitialSetupMigration} only seeds under
 * {@code dev} and {@code test}, and deliberately so: its credentials are fixed and publicly known.
 *
 * <p>This bootstrap ships no default credentials. It does nothing unless
 * {@code gateway.admin.password} is set — most naturally through the {@code GATEWAY_ADMIN_PASSWORD}
 * environment variable — and it does nothing if an account with the configured login already
 * exists. It is therefore safe to leave enabled and safe to re-run.
 *
 * <pre>
 * export GATEWAY_ADMIN_PASSWORD='&lt;a real secret&gt;'
 * </pre>
 *
 * <p>Optional overrides: {@code gateway.admin.login} (default {@code admin}),
 * {@code gateway.admin.email} (default {@code admin@localhost}).
 */
@Component
public class AdminBootstrapInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapInitializer.class);

    private final MongoTemplate template;
    private final PasswordEncoder passwordEncoder;
    private final String login;
    private final String email;
    private final String password;

    public AdminBootstrapInitializer(
        MongoTemplate template,
        PasswordEncoder passwordEncoder,
        @Value("${gateway.admin.login:admin}") String login,
        @Value("${gateway.admin.email:admin@localhost}") String email,
        @Value("${gateway.admin.password:}") String password
    ) {
        this.template = template;
        this.passwordEncoder = passwordEncoder;
        this.login = login;
        this.email = email;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (password == null || password.isBlank()) {
            log.debug("gateway.admin.password is not set; skipping administrator bootstrap");
            return;
        }

        if (template.exists(Query.query(Criteria.where("login").is(login)), User.class)) {
            log.info("Administrator '{}' already exists; skipping bootstrap", login);
            return;
        }

        User admin = new User();
        admin.setLogin(login);
        admin.setPassword(passwordEncoder.encode(password));
        admin.setFirstName("Administrator");
        admin.setLastName("Account");
        admin.setEmail(email);
        admin.setActivated(true);
        admin.setLangKey(Constants.DEFAULT_LANGUAGE);
        admin.setCreatedBy(Constants.SYSTEM);
        admin.setCreatedDate(Instant.now());

        Set<Authority> authorities = new HashSet<>();
        authorities.add(ensureAuthority(AuthoritiesConstants.ADMIN));
        authorities.add(ensureAuthority(AuthoritiesConstants.USER));
        admin.setAuthorities(authorities);

        template.save(admin);
        // Never log the password.
        log.info("Bootstrapped administrator '{}' from gateway.admin.password", login);
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
}
