package net.jojoaddison.config.dbmigrations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import net.jojoaddison.domain.Authority;
import net.jojoaddison.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Unit tests for {@link AuthoritiesMigration}. The companion {@code AuthoritiesSeededIT} covers the part that matters
 * more — that a started application actually runs this.
 */
class AuthoritiesMigrationTest {

    private MongoTemplate template;
    private List<Authority> saved;

    @BeforeEach
    void setUp() {
        template = mock(MongoTemplate.class);
        saved = new ArrayList<>();
        when(template.findById(any(), any())).thenReturn(null);
        when(template.save(any(Authority.class))).thenAnswer(invocation -> {
            saved.add(invocation.getArgument(0));
            return invocation.getArgument(0);
        });
    }

    @Test
    @DisplayName("seeds the three authorities the application authorizes on")
    void seedsTheAuthorities() {
        new AuthoritiesMigration(template).changeSet();

        assertThat(saved).extracting(Authority::getName).containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN", "ROLE_OPERATOR");
    }

    @Test
    @DisplayName("creates no accounts — a change unit runs in production too")
    void createsNoAccounts() {
        new AuthoritiesMigration(template).changeSet();

        verify(template, never()).save(any(User.class));
    }

    @Test
    @DisplayName("leaves an authority that already exists untouched")
    void isIdempotent() {
        Authority existing = new Authority();
        existing.setName("ROLE_USER");
        when(template.findById(any(), any())).thenAnswer(invocation -> "ROLE_USER".equals(invocation.getArgument(0)) ? existing : null);

        new AuthoritiesMigration(template).changeSet();

        assertThat(saved).extracting(Authority::getName).containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_OPERATOR");
    }
}
