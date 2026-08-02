package net.jojoaddison.config.dbmigrations;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.Authority;
import net.jojoaddison.repository.AuthorityRepository;
import net.jojoaddison.security.AuthoritiesConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Asserts that a started application has its authorities, whatever the profile.
 *
 * <p>This is the test that was missing. When the authority seeding moved into a {@code @Profile({"dev", "test"})}
 * {@code ApplicationRunner}, it stopped running in the integration tests entirely — the profile in force here is
 * {@code testdev}, which matches neither, and {@code ApplicationRunner} beans do not execute under
 * {@code @SpringBootTest} in any case. Nothing said so directly. What failed instead were two assertions in
 * {@code AccountResourceIT} about a user's authorities, because {@code UserService} looks each authority up and
 * quietly drops the ones it cannot find, so every account came out with none.</p>
 *
 * <p>A unit test over the change unit cannot replace this: it would prove the class seeds what it is asked to, not
 * that anything runs it when the application actually starts. That gap is exactly where the defect lived.</p>
 */
@IntegrationTest
class AuthoritiesSeededIT {

    @Autowired
    private AuthorityRepository authorityRepository;

    @Test
    @DisplayName("a started application has ROLE_USER, ROLE_ADMIN and ROLE_OPERATOR")
    void authoritiesArePresentInAStartedApplication() {
        List<Authority> authorities = authorityRepository.findAll().collectList().block();

        assertThat(authorities)
            .as("authorities are seeded by a Mongock change unit so that they exist in every profile, tests included")
            .isNotNull()
            .extracting(Authority::getName)
            .contains(AuthoritiesConstants.USER, AuthoritiesConstants.ADMIN, AuthoritiesConstants.OPERATOR);
    }
}
