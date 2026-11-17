package ec.minimarket.cesta.payment;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class HistorialRedirectController {

    @GetMapping({"/historial", "/mis-compras", "/mis-pedidos"})
    public String redirigirHistorial() {
        return "redirect:/pagos/historial";
    }
}
