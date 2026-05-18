package net.jojoaddison.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import net.jojoaddison.domain.Authority;
import net.jojoaddison.domain.User;
import net.jojoaddison.security.AuthoritiesConstants;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.crypto.password.PasswordEncoder;

class DevelopmentUsersInitializerTest {

    private final MongoTemplate template = mock(MongoTemplate.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final DevelopmentUsersInitializer initializer = new DevelopmentUsersInitializer(template, passwordEncoder);

    @Test
    void shouldCreateDevelopmentUsersWhenMissing() throws Exception {
        Authority userAuthority = new Authority();
        userAuthority.setName(AuthoritiesConstants.USER);
        Authority adminAuthority = new Authority();
        adminAuthority.setName(AuthoritiesConstants.ADMIN);

        when(template.findById(AuthoritiesConstants.USER, Authority.class)).thenReturn(userAuthority);
        when(template.findById(AuthoritiesConstants.ADMIN, Authority.class)).thenReturn(adminAuthority);
        when(template.exists(any(Query.class), eq(User.class))).thenReturn(false, false);
        when(passwordEncoder.encode("user")).thenReturn("encoded-user");
        when(passwordEncoder.encode("admin")).thenReturn("encoded-admin");

        initializer.run(mock(ApplicationArguments.class));

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(template, times(2)).save(userCaptor.capture());

        List<User> savedUsers = userCaptor.getAllValues();
        assertThat(savedUsers)
            .extracting(User::getLogin, User::getPassword, User::getEmail)
            .containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("user", "encoded-user", "user@localhost"),
                org.assertj.core.groups.Tuple.tuple("admin", "encoded-admin", "admin@localhost")
            );
        assertThat(savedUsers)
            .filteredOn(user -> "user".equals(user.getLogin()))
            .singleElement()
            .extracting(User::getAuthorities)
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.iterable(Authority.class))
            .extracting(Authority::getName)
            .containsExactly(AuthoritiesConstants.USER);
        assertThat(savedUsers)
            .filteredOn(user -> "admin".equals(user.getLogin()))
            .singleElement()
            .extracting(User::getAuthorities)
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.iterable(Authority.class))
            .extracting(Authority::getName)
            .containsExactlyInAnyOrder(AuthoritiesConstants.ADMIN, AuthoritiesConstants.USER);
    }

    @Test
    void shouldSkipDevelopmentUsersWhenTheyAlreadyExist() throws Exception {
        Authority userAuthority = new Authority();
        userAuthority.setName(AuthoritiesConstants.USER);
        Authority adminAuthority = new Authority();
        adminAuthority.setName(AuthoritiesConstants.ADMIN);

        when(template.findById(AuthoritiesConstants.USER, Authority.class)).thenReturn(userAuthority);
        when(template.findById(AuthoritiesConstants.ADMIN, Authority.class)).thenReturn(adminAuthority);
        when(template.exists(any(Query.class), eq(User.class))).thenReturn(true, true);

        initializer.run(mock(ApplicationArguments.class));

        verify(passwordEncoder, never()).encode(any());
        verify(template, never()).save(any(User.class));
    }
}
