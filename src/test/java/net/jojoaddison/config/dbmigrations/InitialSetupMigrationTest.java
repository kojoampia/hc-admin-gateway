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

        assertThat(runAndCaptureSavedUsers(15))
            .extracting(User::getLogin, User::getEmail)
            .contains(
                Tuple.tuple("admin", "admin@localhost"),
                Tuple.tuple("operator", "operator@localhost"),
                Tuple.tuple("user", "user@localhost"),
                Tuple.tuple("kampiaaddison", "kampiaaddison@localhost"),
                Tuple.tuple("stetteh", "stetteh@localhost")
            );
    }

    /**
     * The ids are a cross-service contract: hc-admin-service's seed data references the admin and
     * operator ids as {@code managedBy} / {@code createdBy}. They were once "user-1"/"user-2" plus a
     * random UUID for the operator, which left those references dangling and made the operator id
     * change on every startup.
     *
     * <p><b>The twelve office accounts ({@code a14}–{@code a25}) are the same contract, one item
     * later.</b> Item 123 migrated {@code Profile.accountId} in hc-admin-service from the gateway
     * login to the account's {@code User.id}, and the twelve {@code cred-aN} placeholder profiles
     * became real accounts here — so each of these ids is referenced verbatim by a
     * {@code personProfiles} row in that repo's seed. An id changed here without the sibling seed
     * moving is a profile no login can reach, and nothing fails when that happens.
     */
    @Test
    void shouldSeedStableIdsMatchingTheCrossServiceContract() {
        activateProfiles("dev");
        stubExistingAuthorities();
        when(template.exists(any(Query.class), eq(User.class))).thenReturn(false);

        assertThat(runAndCaptureSavedUsers(15))
            .extracting(User::getLogin, User::getId)
            .containsExactlyInAnyOrder(
                Tuple.tuple("admin", "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11"),
                Tuple.tuple("operator", "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a12"),
                Tuple.tuple("user", "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a13"),
                Tuple.tuple("kampiaaddison", "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a14"),
                Tuple.tuple("kfrimpong", "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a15"),
                Tuple.tuple("niosae", "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a16"),
                Tuple.tuple("aserwaa", "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a17"),
                Tuple.tuple("yasantewaa", "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a18"),
                Tuple.tuple("kdarkwa", "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a19"),
                Tuple.tuple("bsarsah", "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a20"),
                Tuple.tuple("esam", "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a21"),
                Tuple.tuple("pbaah", "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a22"),
                Tuple.tuple("kofosu", "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a23"),
                Tuple.tuple("gakator", "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a24"),
                Tuple.tuple("stetteh", "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a25")
            );
    }

    @Test
    void shouldTakePasswordsFromTheJsonRatherThanDerivingThem() {
        activateProfiles("dev");
        stubExistingAuthorities();
        when(template.exists(any(Query.class), eq(User.class))).thenReturn(false);

        // NoOpPasswordEncoder, so the stored value is the cleartext seed password.
        assertThat(runAndCaptureSavedUsers(15))
            .extracting(User::getLogin, User::getPassword)
            .contains(
                Tuple.tuple("admin", "Admin@01234"),
                Tuple.tuple("user", "User@0123"),
                Tuple.tuple("operator", "Operator@1234567"),
                Tuple.tuple("kdarkwa", "Kdarkwa@01234")
            );
    }

    /**
     * The operator carrying {@code ROLE_USER} alongside {@code ROLE_OPERATOR} is deliberate: it
     * matches the blueprint and the convention that every account holds {@code ROLE_USER} as a
     * baseline. An earlier hardcoded path granted only {@code ROLE_OPERATOR}. Do not "tidy" it away.
     */
    @Test
    void shouldAssignAuthoritiesDeclaredInTheJson() {
        activateProfiles("dev");
        stubExistingAuthorities();
        when(template.exists(any(Query.class), eq(User.class))).thenReturn(false);

        List<User> saved = runAndCaptureSavedUsers(15);

        assertAuthorities(saved, "user", AuthoritiesConstants.USER);
        assertAuthorities(saved, "admin", AuthoritiesConstants.ADMIN, AuthoritiesConstants.USER);
        assertAuthorities(saved, "operator", AuthoritiesConstants.OPERATOR, AuthoritiesConstants.USER);
        // The office accounts (item 123) hold the ROLE_USER baseline and nothing else: what an
        // office profile-holder may do in the console is a decision nobody has taken, and under the
        // api's read/write split ROLE_USER reaches nothing under /api/**.
        assertAuthorities(saved, "kampiaaddison", AuthoritiesConstants.USER);
        assertAuthorities(saved, "stetteh", AuthoritiesConstants.USER);
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
            // 3 role accounts plus the 12 office accounts item 123 turned from `cred-aN`
            // placeholders into real users.
            assertThat(seedUsers.getDev()).hasSize(15);
            assertThat(seedUsers.getTest()).hasSize(3);
        }
    }
}
