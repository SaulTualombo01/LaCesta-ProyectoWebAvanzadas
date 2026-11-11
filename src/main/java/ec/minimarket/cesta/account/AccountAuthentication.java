package ec.minimarket.cesta.account;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.stereotype.Component;

@Component
class AccountAuthentication implements AuthenticationProvider {
    private final SupabaseAuth auth;
    private final UUID admin;

    AccountAuthentication(SupabaseAuth auth, @Value("${APP_ADMIN_USER_ID:}") String adminId) {
        this.auth = auth;
        this.admin = adminId.isBlank() ? null : UUID.fromString(adminId.strip());
    }

    @Override
    public Authentication authenticate(Authentication input) {
        String email = normalize(input.getName());
        if (!(input.getCredentials() instanceof String password) || password.isBlank()
                || exceedsBcryptLimit(password) || email.isBlank() || email.length() > 254) {
            throw new BadCredentialsException("Credenciales incorrectas.");
        }
        try {
            var session = auth.signIn(email, password);
            try {
                var user = session == null ? null : session.user();
                if (user == null || user.id() == null || user.anonymous() || user.confirmedAt() == null
                        || session.accessToken() == null || session.accessToken().isBlank()) {
                    throw new BadCredentialsException("Credenciales incorrectas o correo sin confirmar.");
                }
                boolean administrator = user.id().equals(admin);
                var principal = new CestaPrincipal(user.id(), displayName(user, administrator));
                return UsernamePasswordAuthenticationToken.authenticated(principal, null,
                        AuthorityUtils.createAuthorityList(administrator ? "ROLE_ADMIN" : "ROLE_CUSTOMER"));
            } finally {
                auth.revoke(session, "local");
            }
        } catch (SupabaseAuth.Rejected rejected) {
            throw new BadCredentialsException("Credenciales incorrectas o correo sin confirmar.");
        } catch (SupabaseAuth.Unavailable unavailable) {
            throw new AuthenticationServiceException("Supabase Auth no está disponible.");
        }
    }

    @Override
    public boolean supports(Class<?> type) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(type);
    }

    static boolean exceedsBcryptLimit(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length > 72;
    }

    static String normalize(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    private static String displayName(SupabaseAuth.Identity identity, boolean administrator) {
        Object name = identity.metadata() == null ? null : identity.metadata().get("name");
        if (name instanceof String text) {
            String clean = text.replaceAll("[\\p{Cc}\\p{Cf}]", "").strip();
            if (!clean.isEmpty()) {
                int length = Math.min(80, clean.codePointCount(0, clean.length()));
                return clean.substring(0, clean.offsetByCodePoints(0, length));
            }
        }
        return administrator ? "Administrador" : "Cliente";
    }
}
