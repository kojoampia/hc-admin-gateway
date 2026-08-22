package net.jojoaddison.web.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.config.Constants;
import net.jojoaddison.domain.User;
import net.jojoaddison.repository.AuthorityRepository;
import net.jojoaddison.repository.UserRepository;
import net.jojoaddison.security.AuthoritiesConstants;
import net.jojoaddison.service.UserService;
import net.jojoaddison.service.dto.AdminUserDTO;
import net.jojoaddison.service.dto.PasswordChangeDTO;
import net.jojoaddison.web.rest.vm.KeyAndPasswordVM;
import net.jojoaddison.web.rest.vm.ManagedUserVM;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Integration tests for the {@link AccountResource} REST controller.
 */
@AutoConfigureWebTestClient(timeout = IntegrationTest.DEFAULT_TIMEOUT)
@IntegrationTest
class AccountResourceIT {

    static final String TEST_USER_LOGIN = "test";

    @Autowired
    private ObjectMapper om;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuthorityRepository authorityRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private WebTestClient accountWebTestClient;

    @BeforeEach
    public void setup() {
        userRepository.deleteAll().block();
    }

    @Test
    @WithUnauthenticatedMockUser
    void testNonAuthenticatedUser() {
        accountWebTestClient
            .get()
            .uri("/api/authenticate")
            .accept(MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .isEmpty();
    }

    @Test
    @WithMockUser(TEST_USER_LOGIN)
    void testAuthenticatedUser() {
        accountWebTestClient
            .get()
            .uri("/api/authenticate")
            .accept(MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(String.class)
            .isEqualTo(TEST_USER_LOGIN);
    }

    @Test
    @WithMockUser(TEST_USER_LOGIN)
    void testGetExistingAccount() {
        Set<String> authorities = new HashSet<>();
        authorities.add(AuthoritiesConstants.ADMIN);

        AdminUserDTO user = new AdminUserDTO();
        user.setLogin(TEST_USER_LOGIN);
        user.setFirstName("john");
        user.setLastName("doe");
        user.setEmail("john.doe@jhipster.com");
        user.setImageUrl("http://placehold.it/50x50");
        user.setLangKey("en");
        user.setAuthorities(authorities);
        userService.createUser(user).block();

        accountWebTestClient
            .get()
            .uri("/api/account")
            .accept(MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus()
            .isOk()
            .expectHeader()
            .contentType(MediaType.APPLICATION_JSON_VALUE)
            .expectBody()
            .jsonPath("$.login")
            .isEqualTo(TEST_USER_LOGIN)
            .jsonPath("$.firstName")
            .isEqualTo("john")
            .jsonPath("$.lastName")
            .isEqualTo("doe")
            .jsonPath("$.email")
            .isEqualTo("john.doe@jhipster.com")
            .jsonPath("$.imageUrl")
            .isEqualTo("http://placehold.it/50x50")
            .jsonPath("$.langKey")
            .isEqualTo("en")
            .jsonPath("$.authorities")
            .isEqualTo(AuthoritiesConstants.ADMIN);
    }

    @Test
    void testGetUnknownAccount() {
        accountWebTestClient
            .get()
            .uri("/api/account")
            .accept(MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus()
            .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * Anonymous callers get 401, not 201: /api/register is no longer in the permitAll list, so it
     * falls through to the blanket {@code /api/** authenticated()} rule. That is the assertion that
     * matters — re-adding the permitAll entry by accident is exactly how this regresses.
     */
    @Test
    void anonymousCannotRegisterAnAccount() throws Exception {
        ManagedUserVM newUser = new ManagedUserVM();
        newUser.setLogin("would-be-attacker");
        newUser.setPassword("Password@123");
        newUser.setEmail("would-be-attacker@example.com");
        newUser.setLangKey(Constants.DEFAULT_LANGUAGE);

        accountWebTestClient
            .post()
            .uri("/api/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(newUser))
            .exchange()
            .expectStatus()
            .isUnauthorized();

        assertThat(userRepository.findOneByLogin("would-be-attacker").blockOptional()).isEmpty();
    }

    @Test
    void anonymousCannotActivateAnAccount() {
        accountWebTestClient.get().uri("/api/activate?key=anything").exchange().expectStatus().isUnauthorized();
    }

    /**
     * And past the filter chain there is nothing to reach: the handlers are deleted, so even a
     * credentialed caller gets 404. This is what distinguishes "closed" from "closed but still
     * routable" — without it, the pair above would still pass if the handlers came back.
     */
    @Test
    @WithMockUser("registration-is-gone")
    void registrationHandlersNoLongerExist() throws Exception {
        ManagedUserVM newUser = new ManagedUserVM();
        newUser.setLogin("would-be-attacker-2");
        newUser.setPassword("Password@123");
        newUser.setEmail("would-be-attacker-2@example.com");
        newUser.setLangKey(Constants.DEFAULT_LANGUAGE);

        accountWebTestClient
            .post()
            .uri("/api/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(newUser))
            .exchange()
            .expectStatus()
            .isNotFound();

        accountWebTestClient.get().uri("/api/activate?key=anything").exchange().expectStatus().isNotFound();

        assertThat(userRepository.findOneByLogin("would-be-attacker-2").blockOptional()).isEmpty();
    }

    @Test
    @WithMockUser("save-account")
    void testSaveAccount() throws Exception {
        User user = new User();
        user.setLogin("save-account");
        user.setEmail("save-account@example.com");
        user.setPassword(RandomStringUtils.randomAlphanumeric(60));
        user.setActivated(true);
        userRepository.save(user).block();

        AdminUserDTO userDTO = new AdminUserDTO();
        userDTO.setLogin("not-used");
        userDTO.setFirstName("firstname");
        userDTO.setLastName("lastname");
        userDTO.setEmail("save-account@example.com");
        userDTO.setActivated(false);
        userDTO.setImageUrl("http://placehold.it/50x50");
        userDTO.setLangKey(Constants.DEFAULT_LANGUAGE);
        userDTO.setAuthorities(Set.of(AuthoritiesConstants.ADMIN));

        accountWebTestClient
            .post()
            .uri("/api/account")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(userDTO))
            .exchange()
            .expectStatus()
            .isOk();

        User updatedUser = userRepository.findOneByLogin(user.getLogin()).block();
        assertThat(updatedUser.getFirstName()).isEqualTo(userDTO.getFirstName());
        assertThat(updatedUser.getLastName()).isEqualTo(userDTO.getLastName());
        assertThat(updatedUser.getEmail()).isEqualTo(userDTO.getEmail());
        assertThat(updatedUser.getLangKey()).isEqualTo(userDTO.getLangKey());
        assertThat(updatedUser.getPassword()).isEqualTo(user.getPassword());
        assertThat(updatedUser.getImageUrl()).isEqualTo(userDTO.getImageUrl());
        assertThat(updatedUser.isActivated()).isTrue();
        assertThat(updatedUser.getAuthorities()).isEmpty();
    }

    @Test
    @WithMockUser("save-invalid-email")
    void testSaveInvalidEmail() throws Exception {
        User user = new User();
        user.setLogin("save-invalid-email");
        user.setEmail("save-invalid-email@example.com");
        user.setPassword(RandomStringUtils.randomAlphanumeric(60));
        user.setActivated(true);

        userRepository.save(user).block();

        AdminUserDTO userDTO = new AdminUserDTO();
        userDTO.setLogin("not-used");
        userDTO.setFirstName("firstname");
        userDTO.setLastName("lastname");
        userDTO.setEmail("invalid email");
        userDTO.setActivated(false);
        userDTO.setImageUrl("http://placehold.it/50x50");
        userDTO.setLangKey(Constants.DEFAULT_LANGUAGE);
        userDTO.setAuthorities(Set.of(AuthoritiesConstants.ADMIN));

        accountWebTestClient
            .post()
            .uri("/api/account")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(userDTO))
            .exchange()
            .expectStatus()
            .isBadRequest();

        assertThat(userRepository.findOneByEmailIgnoreCase("invalid email").blockOptional()).isNotPresent();
    }

    @Test
    @WithMockUser("save-existing-email")
    void testSaveExistingEmail() throws Exception {
        User user = new User();
        user.setLogin("save-existing-email");
        user.setEmail("save-existing-email@example.com");
        user.setPassword(RandomStringUtils.randomAlphanumeric(60));
        user.setActivated(true);
        userRepository.save(user).block();

        User anotherUser = new User();
        anotherUser.setLogin("save-existing-email2");
        anotherUser.setEmail("save-existing-email2@example.com");
        anotherUser.setPassword(RandomStringUtils.randomAlphanumeric(60));
        anotherUser.setActivated(true);

        userRepository.save(anotherUser).block();

        AdminUserDTO userDTO = new AdminUserDTO();
        userDTO.setLogin("not-used");
        userDTO.setFirstName("firstname");
        userDTO.setLastName("lastname");
        userDTO.setEmail("save-existing-email2@example.com");
        userDTO.setActivated(false);
        userDTO.setImageUrl("http://placehold.it/50x50");
        userDTO.setLangKey(Constants.DEFAULT_LANGUAGE);
        userDTO.setAuthorities(Set.of(AuthoritiesConstants.ADMIN));

        accountWebTestClient
            .post()
            .uri("/api/account")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(userDTO))
            .exchange()
            .expectStatus()
            .isBadRequest();

        User updatedUser = userRepository.findOneByLogin("save-existing-email").block();
        assertThat(updatedUser.getEmail()).isEqualTo("save-existing-email@example.com");
    }

    @Test
    @WithMockUser("save-existing-email-and-login")
    void testSaveExistingEmailAndLogin() throws Exception {
        User user = new User();
        user.setLogin("save-existing-email-and-login");
        user.setEmail("save-existing-email-and-login@example.com");
        user.setPassword(RandomStringUtils.randomAlphanumeric(60));
        user.setActivated(true);
        userRepository.save(user).block();

        AdminUserDTO userDTO = new AdminUserDTO();
        userDTO.setLogin("not-used");
        userDTO.setFirstName("firstname");
        userDTO.setLastName("lastname");
        userDTO.setEmail("save-existing-email-and-login@example.com");
        userDTO.setActivated(false);
        userDTO.setImageUrl("http://placehold.it/50x50");
        userDTO.setLangKey(Constants.DEFAULT_LANGUAGE);
        userDTO.setAuthorities(Set.of(AuthoritiesConstants.ADMIN));

        accountWebTestClient
            .post()
            .uri("/api/account")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(userDTO))
            .exchange()
            .expectStatus()
            .isOk();

        User updatedUser = userRepository.findOneByLogin("save-existing-email-and-login").block();
        assertThat(updatedUser.getEmail()).isEqualTo("save-existing-email-and-login@example.com");
    }

    @Test
    @WithMockUser("change-password-wrong-existing-password")
    void testChangePasswordWrongExistingPassword() throws Exception {
        User user = new User();
        String currentPassword = RandomStringUtils.randomAlphanumeric(60);
        user.setPassword(passwordEncoder.encode(currentPassword));
        user.setLogin("change-password-wrong-existing-password");
        user.setEmail("change-password-wrong-existing-password@example.com");
        userRepository.save(user).block();

        accountWebTestClient
            .post()
            .uri("/api/account/change-password")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(new PasswordChangeDTO("1" + currentPassword, "new password")))
            .exchange()
            .expectStatus()
            .isBadRequest();

        User updatedUser = userRepository.findOneByLogin("change-password-wrong-existing-password").block();
        assertThat(passwordEncoder.matches("new password", updatedUser.getPassword())).isFalse();
        assertThat(passwordEncoder.matches(currentPassword, updatedUser.getPassword())).isTrue();
    }

    @Test
    @WithMockUser("change-password")
    void testChangePassword() throws Exception {
        User user = new User();
        String currentPassword = RandomStringUtils.randomAlphanumeric(60);
        user.setPassword(passwordEncoder.encode(currentPassword));
        user.setLogin("change-password");
        user.setEmail("change-password@example.com");
        userRepository.save(user).block();

        accountWebTestClient
            .post()
            .uri("/api/account/change-password")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(new PasswordChangeDTO(currentPassword, "new password")))
            .exchange()
            .expectStatus()
            .isOk();

        User updatedUser = userRepository.findOneByLogin("change-password").block();
        assertThat(passwordEncoder.matches("new password", updatedUser.getPassword())).isTrue();
    }

    @Test
    @WithMockUser("change-password-too-small")
    void testChangePasswordTooSmall() throws Exception {
        User user = new User();
        String currentPassword = RandomStringUtils.randomAlphanumeric(60);
        user.setPassword(passwordEncoder.encode(currentPassword));
        user.setLogin("change-password-too-small");
        user.setEmail("change-password-too-small@example.com");
        userRepository.save(user).block();

        String newPassword = RandomStringUtils.random(ManagedUserVM.PASSWORD_MIN_LENGTH - 1);

        accountWebTestClient
            .post()
            .uri("/api/account/change-password")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(new PasswordChangeDTO(currentPassword, newPassword)))
            .exchange()
            .expectStatus()
            .isBadRequest();

        User updatedUser = userRepository.findOneByLogin("change-password-too-small").block();
        assertThat(updatedUser.getPassword()).isEqualTo(user.getPassword());
    }

    @Test
    @WithMockUser("change-password-too-long")
    void testChangePasswordTooLong() throws Exception {
        User user = new User();
        String currentPassword = RandomStringUtils.randomAlphanumeric(60);
        user.setPassword(passwordEncoder.encode(currentPassword));
        user.setLogin("change-password-too-long");
        user.setEmail("change-password-too-long@example.com");
        userRepository.save(user).block();

        String newPassword = RandomStringUtils.random(ManagedUserVM.PASSWORD_MAX_LENGTH + 1);

        accountWebTestClient
            .post()
            .uri("/api/account/change-password")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(new PasswordChangeDTO(currentPassword, newPassword)))
            .exchange()
            .expectStatus()
            .isBadRequest();

        User updatedUser = userRepository.findOneByLogin("change-password-too-long").block();
        assertThat(updatedUser.getPassword()).isEqualTo(user.getPassword());
    }

    @Test
    @WithMockUser("change-password-empty")
    void testChangePasswordEmpty() throws Exception {
        User user = new User();
        String currentPassword = RandomStringUtils.randomAlphanumeric(60);
        user.setPassword(passwordEncoder.encode(currentPassword));
        user.setLogin("change-password-empty");
        user.setEmail("change-password-empty@example.com");
        userRepository.save(user).block();

        accountWebTestClient
            .post()
            .uri("/api/account/change-password")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(new PasswordChangeDTO(currentPassword, "")))
            .exchange()
            .expectStatus()
            .isBadRequest();

        User updatedUser = userRepository.findOneByLogin("change-password-empty").block();
        assertThat(updatedUser.getPassword()).isEqualTo(user.getPassword());
    }

    @Test
    void testRequestPasswordReset() {
        User user = new User();
        user.setPassword(RandomStringUtils.randomAlphanumeric(60));
        user.setActivated(true);
        user.setLogin("password-reset");
        user.setEmail("password-reset@example.com");
        user.setLangKey("en");
        userRepository.save(user).block();

        accountWebTestClient
            .post()
            .uri("/api/account/reset-password/init")
            .bodyValue("password-reset@example.com")
            .exchange()
            .expectStatus()
            .isOk();
    }

    @Test
    void testRequestPasswordResetUpperCaseEmail() {
        User user = new User();
        user.setPassword(RandomStringUtils.randomAlphanumeric(60));
        user.setActivated(true);
        user.setLogin("password-reset-upper-case");
        user.setEmail("password-reset-upper-case@example.com");
        user.setLangKey("en");
        userRepository.save(user).block();

        accountWebTestClient
            .post()
            .uri("/api/account/reset-password/init")
            .bodyValue("password-reset-upper-case@EXAMPLE.COM")
            .exchange()
            .expectStatus()
            .isOk();
    }

    @Test
    void testRequestPasswordResetWrongEmail() {
        accountWebTestClient
            .post()
            .uri("/api/account/reset-password/init")
            .bodyValue("password-reset-wrong-email@example.com")
            .exchange()
            .expectStatus()
            .isOk();
    }

    @Test
    void testFinishPasswordReset() throws Exception {
        User user = new User();
        user.setPassword(RandomStringUtils.randomAlphanumeric(60));
        user.setLogin("finish-password-reset");
        user.setEmail("finish-password-reset@example.com");
        user.setResetDate(Instant.now().plusSeconds(60));
        user.setResetKey("reset key");
        userRepository.save(user).block();

        KeyAndPasswordVM keyAndPassword = new KeyAndPasswordVM();
        keyAndPassword.setKey(user.getResetKey());
        keyAndPassword.setNewPassword("new password");

        accountWebTestClient
            .post()
            .uri("/api/account/reset-password/finish")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(keyAndPassword))
            .exchange()
            .expectStatus()
            .isOk();

        User updatedUser = userRepository.findOneByLogin(user.getLogin()).block();
        assertThat(passwordEncoder.matches(keyAndPassword.getNewPassword(), updatedUser.getPassword())).isTrue();
    }

    @Test
    void testFinishPasswordResetTooSmall() throws Exception {
        User user = new User();
        user.setPassword(RandomStringUtils.randomAlphanumeric(60));
        user.setLogin("finish-password-reset-too-small");
        user.setEmail("finish-password-reset-too-small@example.com");
        user.setResetDate(Instant.now().plusSeconds(60));
        user.setResetKey("reset key too small");
        userRepository.save(user).block();

        KeyAndPasswordVM keyAndPassword = new KeyAndPasswordVM();
        keyAndPassword.setKey(user.getResetKey());
        keyAndPassword.setNewPassword("foo");

        accountWebTestClient
            .post()
            .uri("/api/account/reset-password/finish")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(keyAndPassword))
            .exchange()
            .expectStatus()
            .isBadRequest();

        User updatedUser = userRepository.findOneByLogin(user.getLogin()).block();
        assertThat(passwordEncoder.matches(keyAndPassword.getNewPassword(), updatedUser.getPassword())).isFalse();
    }

    @Test
    void testFinishPasswordResetWrongKey() throws Exception {
        KeyAndPasswordVM keyAndPassword = new KeyAndPasswordVM();
        keyAndPassword.setKey("wrong reset key");
        keyAndPassword.setNewPassword("new password");

        accountWebTestClient
            .post()
            .uri("/api/account/reset-password/finish")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(keyAndPassword))
            .exchange()
            .expectStatus()
            .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
