package ec.minimarket.cesta.cart;

import ec.minimarket.cesta.account.CestaPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.servlet.HandlerMapping;

@ControllerAdvice
public class CarritoAdvice {

    private final CarritoService carritoService;

    public CarritoAdvice(CarritoService carritoService) {
        this.carritoService = carritoService;
    }

    @ModelAttribute("carritoCantidad")
    public int carritoCantidad(Authentication authentication, HttpServletRequest request) {
        if ("GET".equals(request.getMethod()) && authentication != null
                && authentication.getPrincipal() instanceof CestaPrincipal principal
                && request.isUserInRole("CUSTOMER")
                && !"/productos/{id}/imagen".equals(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE))) {
            return carritoService.contarUnidades(principal.id());
        }
        return 0;
    }
}
