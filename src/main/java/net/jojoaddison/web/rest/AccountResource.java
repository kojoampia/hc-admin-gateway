package net.jojoaddison.web.rest;

import jakarta.validation.Valid;
import java.util.Objects;
import net.jojoaddison.repository.UserRepository;
import net.jojoaddison.security.SecurityUtils;
import net.jojoaddison.service.MailService;
import net.jojoaddison.service.UserService;
import net.jojoaddison.service.dto.AdminUserDTO;
import net.jojoaddison.service.dto.PasswordChangeDTO;
import net.jojoaddison.web.rest.errors.*;
import net.jojoaddison.web.rest.vm.KeyAndPasswordVM;
import net.jojoaddison.web.rest.vm.ManagedUserVM;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

/**
 * REST controller for managing the current user's account.
 */
@RestController
@RequestMapping("/api")
public class AccountResource {

    private static class AccountResourceException extends RuntimeException {

        private AccountResourceException(String message) {
            super(message);
        }
    }

    private final Logger log = LoggerFactory.getLogger(AccountResource.class);

    private final UserRepository userRepository;

    private final UserService userService;

    private final MailService mailService;

    public AccountResource(UserRepository userRepository, UserService userService, MailService mailService) {
        this.userRepository = userRepository;
        this.userService = userService;
        this.mailService = mailService;
    }

    // There is deliberately no POST /register and no GET /activate.
    //
    // This is an internal administrative console. Self-registration granted ROLE_USER to anyone who
    // could reach the login page, and the downstream service admitted any authenticated principal —
    // so the two together were a path from the open internet to every organisation, facility and
    // patient subscription in the system. Accounts are created by an admin through
    // /api/admin/users (UserResource, ROLE_ADMIN on every method), which sets `activated` directly
    // and needs no activation key.
    //
    // UserService.registerUser and activateRegistration are likewise gone. If self-service signup is
    // ever wanted here, it needs an approval step and a default authority that grants nothing —
    // not a revival of these two handlers.

    /**
     * {@code GET  /account} : get the current user.
     *
     * @return the current user.
     * @throws RuntimeException {@code 500 (Internal Server Error)} if the user couldn't be returned.
     */
    @GetMapping("/account")
    public Mono<AdminUserDTO> getAccount() {
        return userService
            .getUserWithAuthorities()
            .map(AdminUserDTO::new)
            .switchIfEmpty(Mono.error(new AccountResourceException("User could not be found")));
    }

    /**
     * {@code POST  /account} : update the current user information.
     *
     * @param userDTO the current user information.
     * @throws EmailAlreadyUsedException {@code 400 (Bad Request)} if the email is already used.
     * @throws RuntimeException {@code 500 (Internal Server Error)} if the user login wasn't found.
     */
    @PostMapping("/account")
    public Mono<Void> saveAccount(@Valid @RequestBody AdminUserDTO userDTO) {
        return SecurityUtils.getCurrentUserLogin()
            .switchIfEmpty(Mono.error(new AccountResourceException("Current user login not found")))
            .flatMap(userLogin ->
                userRepository
                    .findOneByEmailIgnoreCase(userDTO.getEmail())
                    .filter(existingUser -> !existingUser.getLogin().equalsIgnoreCase(userLogin))
                    .hasElement()
                    .flatMap(emailExists -> {
                        if (emailExists) {
                            throw new EmailAlreadyUsedException();
                        }
                        return userRepository.findOneByLogin(userLogin);
                    }))
            .switchIfEmpty(Mono.error(new AccountResourceException("User could not be found")))
            .flatMap(
                user ->
                    userService.updateUser(
                        userDTO.getFirstName(),
                        userDTO.getLastName(),
                        userDTO.getEmail(),
                        userDTO.getLangKey(),
                        userDTO.getImageUrl()
                    )
            );
    }

    /**
     * {@code POST  /account/change-password} : changes the current user's password.
     *
     * @param passwordChangeDto current and new password.
     * @throws InvalidPasswordException {@code 400 (Bad Request)} if the new password is incorrect.
     */
    @PostMapping(path = "/account/change-password")
    public Mono<Void> changePassword(@RequestBody PasswordChangeDTO passwordChangeDto) {
        if (isPasswordLengthInvalid(passwordChangeDto.getNewPassword())) {
            throw new InvalidPasswordException();
        }
        return userService.changePassword(passwordChangeDto.getCurrentPassword(), passwordChangeDto.getNewPassword());
    }

    /**
     * {@code POST   /account/reset-password/init} : Send an email to reset the password of the user.
     *
     * @param mail the mail of the user.
     */
    @PostMapping(path = "/account/reset-password/init")
    public Mono<Void> requestPasswordReset(@RequestBody String mail) {
        return userService
            .requestPasswordReset(mail)
            .doOnSuccess(user -> {
                if (Objects.nonNull(user)) {
                    mailService.sendPasswordResetMail(user);
                } else {
                    // Pretend the request has been successful to prevent checking which emails really exist
                    // but log that an invalid attempt has been made
                    log.warn("Password reset requested for non existing mail");
                }
            })
            .then();
    }

    /**
     * {@code POST   /account/reset-password/finish} : Finish to reset the password of the user.
     *
     * @param keyAndPassword the generated key and the new password.
     * @throws InvalidPasswordException {@code 400 (Bad Request)} if the password is incorrect.
     * @throws RuntimeException {@code 500 (Internal Server Error)} if the password could not be reset.
     */
    @PostMapping(path = "/account/reset-password/finish")
    public Mono<Void> finishPasswordReset(@RequestBody KeyAndPasswordVM keyAndPassword) {
        if (isPasswordLengthInvalid(keyAndPassword.getNewPassword())) {
            throw new InvalidPasswordException();
        }
        return userService
            .completePasswordReset(keyAndPassword.getNewPassword(), keyAndPassword.getKey())
            .switchIfEmpty(Mono.error(new AccountResourceException("No user was found for this reset key")))
            .then();
    }

    private static boolean isPasswordLengthInvalid(String password) {
        return (
            StringUtils.isEmpty(password) ||
            password.length() < ManagedUserVM.PASSWORD_MIN_LENGTH ||
            password.length() > ManagedUserVM.PASSWORD_MAX_LENGTH
        );
    }
}
