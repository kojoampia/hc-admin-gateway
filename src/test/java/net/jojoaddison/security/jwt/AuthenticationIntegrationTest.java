package net.jojoaddison.security.jwt;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import net.jojoaddison.AdminGatewayApp;
import net.jojoaddison.config.EmbeddedMongo;
import net.jojoaddison.config.SecurityConfiguration;
import net.jojoaddison.config.SecurityJwtConfiguration;
import net.jojoaddison.config.WebConfigurer;
import net.jojoaddison.management.SecurityMetersService;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import({
    WebConfigurer.class,
    SecurityConfiguration.class,
    SecurityJwtConfiguration.class,
    SecurityMetersService.class,
    JwtAuthenticationTestUtils.class,
})
@SpringBootTest(
    classes = AdminGatewayApp.class,
    properties = {
        "jhipster.security.authentication.jwt.base64-secret=fd54a45s65fds737b9aafcb3412e07ed99b267f33413274720ddbb7f6c5e64e9f14075f2d7ed041592f0b7657baf8",
        "jhipster.security.authentication.jwt.token-validity-in-seconds=60000",
    }
)
@EmbeddedMongo
// No @EmbeddedKafka. It was here and on @IntegrationTest, and between them they started a Kafka
// container for every class in this repository that boots a context — a broker this gateway never
// contacts, because it declares no stream functions and no bindings (backlog items 40a and 17).
// Two of the three classes this annotation is for — TokenAuthenticationIT and
// TokenAuthenticationSecurityMetersIT — are exactly the ones a failed container start has kept naming
// for changes that touch neither of them.
public @interface AuthenticationIntegrationTest {}
