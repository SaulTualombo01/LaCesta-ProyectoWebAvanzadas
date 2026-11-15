package ec.minimarket.cesta.cart;

import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
public class CarritoAdvice {

    private final CarritoService carritoService;

    public CarritoAdvice(CarritoService carritoService) {
        this.carritoService = carritoService;
    }

    @ModelAttribute("carritoCantidad")
    public int carritoCantidad(HttpSession session) {
        return carritoService.contarUnidades(session);
    }
}
