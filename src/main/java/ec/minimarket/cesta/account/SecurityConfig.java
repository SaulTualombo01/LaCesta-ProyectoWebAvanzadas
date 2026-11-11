package ec.minimarket.cesta.account;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.session.HttpSessionEventPublisher;

@Configuration
class SecurityConfig {
    @Bean
    SecurityFilterChain security(HttpSecurity http, AccountAuthentication authentication, SessionRegistry sessions,
                                 @Value("${server.servlet.session.cookie.secure}") boolean requireHttps) throws Exception {
        if (requireHttps) {
            http.redirectToHttps(Customizer.withDefaults());
        }
        http.authenticationProvider(authentication)
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/ingresar", "/registro", "/confirmar", "/recuperar", "/restablecer", "/error",
                                "/css/**", "/js/**", "/img/**", "/fonts/**", "/favicon.ico").permitAll()
                        .requestMatchers("/admin", "/admin/**").hasRole("ADMIN")
                        .requestMatchers("/productos/nuevo", "/productos/*/editar").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/productos/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .sessionManagement(session -> session.maximumSessions(-1)
                        .sessionRegistry(sessions).expiredUrl("/ingresar?sesion"))
                .formLogin(login -> login.loginPage("/ingresar")
                        .usernameParameter("email")
                        .failureHandler((request, response, failure) -> response.sendRedirect(
                                request.getContextPath() + (failure instanceof AuthenticationServiceException
                                        ? "/ingresar?servicio" : "/ingresar?error")))
                        .defaultSuccessUrl("/", true))
                .logout(logout -> logout.logoutUrl("/salir").logoutSuccessUrl("/ingresar?logout"))
                .headers(headers -> headers
                        .referrerPolicy(policy -> policy.policy(ReferrerPolicy.NO_REFERRER))
                        .contentSecurityPolicy(policy -> policy.policyDirectives(
                                "default-src 'self'; img-src 'self' data:; object-src 'none'; "
                                        + "base-uri 'none'; form-action 'self'; frame-ancestors 'none'")));

        return http.build();
    }

    @Bean
    SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    @Bean
    HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }
}
