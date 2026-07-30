package net.jojoaddison.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import net.jojoaddison.domain.Authority;
import net.jojoaddison.domain.User;
import net.jojoaddison.security.AuthoritiesConstants;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Covers the production administrator bootstrap.
 *
 * <p>The contract that matters: it ships no default credentials, so it must stay inert unless a
 * password is explicitly configured, and it must never overwrite an existing account.
 */
class AdminBootstrapInitializerTest {

    private final MongoTemplate template = mock(MongoTemplate.class);

    @SuppressWarnings("deprecation")
    private final PasswordEncoder passwordEncoder = NoOpPasswordEncoder.getInstance();

    private AdminBootstrapInitializer initializer(String password) {
        return new AdminBootstrapInitializer(template, passwordEncoder, "admin", "admin@localhost", password);
    }

    @Test
    void shouldDoNothingWhenNoPasswordIsConfigured() {
        initializer("").run(mock(ApplicationArguments.class));

        verify(template, never()).save(any());
        verify(template, never()).exists(any(Query.class), eq(User.class));
    }

    @Test
    void shouldDoNothingWhenPasswordIsBlank() {
        initializer("   ").run(mock(ApplicationArguments.class));

        verify(template, never()).save(any());
    }

    @Test
    void shouldDoNothingWhenPasswordIsNull() {
        initializer(null).run(mock(ApplicationArguments.class));

        verify(template, never()).save(any());
    }

    @Test
    void shouldNotOverwriteAnExistingAdministrator() {
        when(template.exists(any(Query.class), eq(User.class))).thenReturn(true);

        initializer("a-real-secret").run(mock(ApplicationArguments.class));

        verify(template, never()).save(any(User.class));
    }

    @Test
    void shouldCreateAdministratorWhenPasswordIsSetAndNoneExists() {
        when(template.exists(any(Query.class), eq(User.class))).thenReturn(false);
        Authority admin = new Authority();
        admin.setName(AuthoritiesConstants.ADMIN);
        Authority user = new Authority();
        user.setName(AuthoritiesConstants.USER);
        when(template.findById(AuthoritiesConstants.ADMIN, Authority.class)).thenReturn(admin);
        when(template.findById(AuthoritiesConstants.USER, Authority.class)).thenReturn(user);

        initializer("a-real-secret").run(mock(ApplicationArguments.class));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(template).save(captor.capture());

        User saved = captor.getValue();
        assertThat(saved.getLogin()).isEqualTo("admin");
        assertThat(saved.getEmail()).isEqualTo("admin@localhost");
        assertThat(saved.isActivated()).isTrue();
        // NoOpPasswordEncoder, so the stored value is the configured secret.
        assertThat(saved.getPassword()).isEqualTo("a-real-secret");
        assertThat(saved.getAuthorities())
            .asInstanceOf(InstanceOfAssertFactories.iterable(Authority.class))
            .extracting(Authority::getName)
            .containsExactlyInAnyOrder(AuthoritiesConstants.ADMIN, AuthoritiesConstants.USER);
    }

    @Test
    void shouldCreateMissingAuthorities() {
        when(template.exists(any(Query.class), eq(User.class))).thenReturn(false);
        when(template.findById(anyString(), eq(Authority.class))).thenReturn(null);
        when(template.save(any(Authority.class))).thenAnswer(invocation -> invocation.getArgument(0));

        initializer("a-real-secret").run(mock(ApplicationArguments.class));

        ArgumentCaptor<Authority> captor = ArgumentCaptor.forClass(Authority.class);
        verify(template, org.mockito.Mockito.atLeast(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(Authority::getName).contains(AuthoritiesConstants.ADMIN, AuthoritiesConstants.USER);
    }

    @Test
    void shouldHonourConfiguredLoginAndEmail() {
        when(template.exists(any(Query.class), eq(User.class))).thenReturn(false);
        when(template.findById(anyString(), eq(Authority.class))).thenAnswer(invocation -> {
            Authority authority = new Authority();
            authority.setName(invocation.getArgument(0));
            return authority;
        });

        new AdminBootstrapInitializer(template, passwordEncoder, "root", "root@example.com", "a-real-secret").run(
            mock(ApplicationArguments.class)
        );

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(template).save(captor.capture());
        assertThat(captor.getValue().getLogin()).isEqualTo("root");
        assertThat(captor.getValue().getEmail()).isEqualTo("root@example.com");
    }
}
