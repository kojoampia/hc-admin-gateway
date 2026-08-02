package net.jojoaddison.config.dbmigrations;

import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import net.jojoaddison.domain.Authority;
import net.jojoaddison.security.AuthoritiesConstants;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Seeds the authorities — {@code ROLE_USER}, {@code ROLE_ADMIN} and {@code ROLE_OPERATOR} — in every profile.
 *
 * <p>Authorities are structural rather than sample data. Registration grants {@code ROLE_USER} to each new account by
 * looking it up with {@code authorityRepository.findById}, and {@link net.jojoaddison.service.UserService} silently
 * drops an authority it cannot find. A database without these documents therefore does not fail loudly: it creates
 * users with no authorities at all.</p>
 *
 * <p>This is a change unit, and deliberately not an {@code ApplicationRunner}, for two reasons that were each enough on
 * their own to break the integration tests when the seeding moved into {@code InitialSetupMigration}:</p>
 *
 * <ol>
 *   <li>That class is gated with {@code @Profile({"dev", "test"})}, but the profile these tests actually run under is
 *       {@code testdev} — one profile with that literal name, set by {@code <profile.test>} in the POM. Neither
 *       {@code dev} nor {@code test} matches it, so the bean was never created.</li>
 *   <li>Even ungated it would not have helped: {@code ApplicationRunner} beans are not executed under
 *       {@code @SpringBootTest}. Only a real {@code SpringApplication.run} invokes them.</li>
 * </ol>
 *
 * <p>A Mongock change unit runs during context initialisation, in every profile, so it applies in tests and in
 * production alike. Accounts stay where they belong — {@link InitialSetupMigration} for the development logins, and
 * {@link net.jojoaddison.config.AdminBootstrapInitializer} for the credential-free production administrator.</p>
 *
 * <p>The id is new rather than the {@code users-initialization} this seeding once lived under. Mongock records each
 * change unit as executed and never repeats it, so reusing that id would skip every database that already ran it —
 * which is every existing environment.</p>
 */
@ChangeUnit(id = "authorities-initialization", order = "002")
public class AuthoritiesMigration {

    private final MongoTemplate template;

    public AuthoritiesMigration(MongoTemplate template) {
        this.template = template;
    }

    @Execution
    public void changeSet() {
        ensureAuthority(AuthoritiesConstants.USER);
        ensureAuthority(AuthoritiesConstants.ADMIN);
        ensureAuthority(AuthoritiesConstants.OPERATOR);
    }

    @RollbackExecution
    public void rollback() {}

    /**
     * Creates the authority unless a document with that name already exists, so this is safe against a database that
     * has been seeded before by any other means.
     *
     * @param name the authority name, e.g. {@code ROLE_USER}.
     */
    private void ensureAuthority(String name) {
        if (template.findById(name, Authority.class) != null) {
            return;
        }
        Authority authority = new Authority();
        authority.setName(name);
        template.save(authority);
    }
}
