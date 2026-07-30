package net.jojoaddison.config.dbmigrations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.List;
import net.jojoaddison.domain.Authority;
import net.jojoaddison.domain.User;
import net.jojoaddison.security.AuthoritiesConstants;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Covers seeding of local accounts from {@code hc-admin-gw-data.json}.
 *
 * <p>Replaces {@code DevelopmentUsersInitializerTest}, which targeted a class removed when seeding
 * was consolidated here. Beyond create/skip behaviour these tests pin three things that previously
 * regressed: the collections are never dropped, the seed ids are stable and match the cross-service
 * contract, and passwords come from the JSON rather than being derived in code.
 */
class InitialSetupMigrationTest {

    private final MongoTemplate template = mock(MongoTemplate.class);

    @SuppressWarnings("deprecation")
    private final PasswordEncoder passwordEncoder = NoOpPasswordEncoder.getInstance();

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Environment environment = mock(Environment.class);

    private final InitialSetupMigration migration = new InitialSetupMigration(template, passwordEncoder, objectMapper, environment);

    private void activateProfiles(String... profiles) {
        List<String> active = List.of(profiles);
        when(environment.acceptsProfiles(any(Profiles.class))).thenAnswer(invocation -> {
            Profiles requested = invocation.getArgument(0);
            return requested.matches(active::contains);
        });
    }

    private void stubExistingAuthorities() {
        for (String name : List.of(AuthoritiesConstants.USER, AuthoritiesConstants.ADMIN, AuthoritiesConstants.OPERATOR)) {
            Authority authority = new Authority();
            authority.setName(name);
            when(template.findById(name, Authority.class)).thenReturn(authority);
        }
    }

    private List<User> runAndCaptureSavedUsers(int expectedCount) {
        migration.run(mock(ApplicationArguments.class));
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(template, times(expectedCount)).save(captor.capture());
        return captor.getAllValues();
    }

    @Test
    void shouldSeedDevUsersFromJsonWhenMissing() {
        activateProfiles("dev");
        stubExistingAuthorities();
        when(template.exists(any(Query.class), eq(User.class))).thenReturn(false);

        assertThat(runAndCaptureSavedUsers(3))
            .extracting(User::getLogin, User::getEmail)
            .containsExactlyInAnyOrder(
                Tuple.tuple("admin", "admin@localhost"),
                Tuple.tuple("operator", "operator@localhost"),
                Tuple.tuple("user", "user@localhost")
            );
    }

    /**
     * The ids are a cross-service contract: hc-admin-service's seed data references the admin and
     * operator ids as {@code managedBy} / {@code createdBy}. They were once "user-1"/"user-2" plus a
     * random UUID for the operator, which left those references dangling and made the operator id
     * change on every startup.
     */
    @Test
    void shouldSeedStableIdsMatchingTheCrossServiceContract() {
        activateProfiles("dev");
        stubExistingAuthorities();
        when(template.exists(any(Query.class), eq(User.class))).thenReturn(false);

        assertThat(runAndCaptureSavedUsers(3))
            .extracting(User::getLogin, User::getId)
            .containsExactlyInAnyOrder(
                Tuple.tuple("admin", "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11"),
                Tuple.tuple("operator", "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a12"),
                Tuple.tuple("user", "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a13")
            );
    }

    @Test
    void shouldTakePasswordsFromTheJsonRatherThanDerivingThem() {
        activateProfiles("dev");
        stubExistingAuthorities();
        when(template.exists(any(Query.class), eq(User.class))).thenReturn(false);

        // NoOpPasswordEncoder, so the stored value is the cleartext seed password.
        assertThat(runAndCaptureSavedUsers(3))
            .extracting(User::getLogin, User::getPassword)
            .containsExactlyInAnyOrder(
                Tuple.tuple("admin", "Admin@01234"),
                Tuple.tuple("user", "User@0123"),
                Tuple.tuple("operator", "Operator@1234567")
            );
    }

    @Test
    void shouldAssignAuthoritiesDeclaredInTheJson() {
        activateProfiles("dev");
        stubExistingAuthorities();
        when(template.exists(any(Query.class), eq(User.class))).thenReturn(false);

        List<User> saved = runAndCaptureSavedUsers(3);

        assertAuthorities(saved, "user", AuthoritiesConstants.USER);
        assertAuthorities(saved, "admin", AuthoritiesConstants.ADMIN, AuthoritiesConstants.USER);
        assertAuthorities(saved, "operator", AuthoritiesConstants.OPERATOR, AuthoritiesConstants.USER);
    }

    private void assertAuthorities(List<User> savedUsers, String login, String... expected) {
        assertThat(savedUsers)
            .filteredOn(user -> login.equals(user.getLogin()))
            .singleElement()
            .extracting(User::getAuthorities)
            .asInstanceOf(InstanceOfAssertFactories.iterable(Authority.class))
            .extracting(Authority::getName)
            .containsExactlyInAnyOrder(expected);
    }

    @Test
    void shouldSeedTestFixturesUnderTheTestProfile() {
        activateProfiles("test");
        stubExistingAuthorities();
        when(template.exists(any(Query.class), eq(User.class))).thenReturn(false);

        List<User> saved = runAndCaptureSavedUsers(3);

        assertThat(saved).extracting(User::getLogin).containsExactlyInAnyOrder("deactivated", "noauth", "malformed");
        assertThat(saved)
            .filteredOn(user -> "deactivated".equals(user.getLogin()))
            .singleElement()
            .extracting(User::isActivated)
            .isEqualTo(false);
        // `noauth` declares an empty authorities array.
        assertAuthorities(saved, "noauth");
        // Fixtures carry no password, so the login is used as a fallback.
        assertThat(saved)
            .filteredOn(user -> "noauth".equals(user.getLogin()))
            .singleElement()
            .extracting(User::getPassword)
            .isEqualTo("noauth");
    }

    @Test
    void shouldSkipUsersThatAlreadyExist() {
        activateProfiles("dev");
        stubExistingAuthorities();
        when(template.exists(any(Query.class), eq(User.class))).thenReturn(true);

        migration.run(mock(ApplicationArguments.class));

        verify(template, never()).save(any(User.class));
    }

    /**
     * Regression guard. The constructor once called a {@code cleanup()} that dropped the
     * {@code User} and {@code Authority} collections on every start, destroying any account created
     * through the API. Seeding must be additive.
     */
    @Test
    void shouldNeverDropCollections() {
        activateProfiles("dev");
        stubExistingAuthorities();
        when(template.exists(any(Query.class), eq(User.class))).thenReturn(false);

        migration.run(mock(ApplicationArguments.class));

        verify(template, never()).dropCollection(User.class);
        verify(template, never()).dropCollection(Authority.class);
        verify(template, never()).dropCollection(anyString());
    }

    @Test
    void shouldCreateMissingAuthorities() {
        activateProfiles("dev");
        when(template.findById(anyString(), eq(Authority.class))).thenReturn(null);
        when(template.exists(any(Query.class), eq(User.class))).thenReturn(false);
        when(template.save(any(Authority.class))).thenAnswer(invocation -> invocation.getArgument(0));

        migration.run(mock(ApplicationArguments.class));

        ArgumentCaptor<Authority> captor = ArgumentCaptor.forClass(Authority.class);
        verify(template, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues())
            .extracting(Authority::getName)
            .contains(AuthoritiesConstants.ADMIN, AuthoritiesConstants.USER, AuthoritiesConstants.OPERATOR);
    }

    @Test
    void shouldSeedNothingWhenNeitherDevNorTestIsActive() {
        activateProfiles("prod");

        migration.run(mock(ApplicationArguments.class));

        verify(template, never()).save(any(User.class));
    }

    /** The seed file must stay on the classpath at the location the migration reads. */
    @Test
    void seedFileShouldBePresentAndParseable() throws Exception {
        try (InputStream inputStream = new ClassPathResource(InitialSetupMigration.SEED_DATA_LOCATION).getInputStream()) {
            InitialSetupMigration.SeedUsers seedUsers = objectMapper.readValue(inputStream, InitialSetupMigration.SeedUsers.class);
            assertThat(seedUsers.getDev()).hasSize(3);
            assertThat(seedUsers.getTest()).hasSize(3);
        }
    }
}
