package ec.minimarket.cesta.account;

import java.security.Principal;
import java.util.UUID;

public record CestaPrincipal(UUID id, String displayName) implements Principal {
    @Override
    public String getName() {
        return id.toString();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CestaPrincipal principal && id.equals(principal.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
