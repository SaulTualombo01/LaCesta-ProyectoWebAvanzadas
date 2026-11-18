package ec.minimarket.cesta.payment;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
class HistorialRedirectController {

    @GetMapping({"/historial", "/mis-compras", "/mis-pedidos"})
    public String redirigirHistorial(@RequestParam(defaultValue = "0") int page, RedirectAttributes redirect) {
        if (page > 0) {
            redirect.addAttribute("page", page);
        }
        return "redirect:/pagos/historial";
    }
}
