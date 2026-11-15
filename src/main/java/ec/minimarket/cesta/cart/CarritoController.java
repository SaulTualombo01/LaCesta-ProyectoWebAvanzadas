package ec.minimarket.cesta.cart;

import ec.minimarket.cesta.account.CestaPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/carrito")
public class CarritoController {

    private static final String EXITO = "exito";
    private static final String ERROR = "error";
    private static final String REDIRECT_CARRITO = "redirect:/carrito";

    private final CarritoService carrito;

    public CarritoController(CarritoService carrito) {
        this.carrito = carrito;
    }

    @GetMapping
    public String verCarrito(
            HttpSession session,
            Authentication authentication,
            Model model) {

        ResumenCarrito resumen = carrito.obtenerResumen(session);
        model.addAttribute("resumen", resumen);
        model.addAttribute("carritoCantidad", resumen.totalUnidades());

        if (!model.containsAttribute("entrega")) {
            DatosEntregaForm entregaGuardada = carrito.obtenerDatosEntrega(session);
            model.addAttribute("entrega", entregaGuardada);
            model.addAttribute(
                    "entregaAutocompletada",
                    !entregaGuardada.direccion().isBlank()
            );
        }

        return "cart/index";
    }

    @PostMapping("/agregar")
    public String agregar(
            @RequestParam Long productoId,
            @RequestParam(defaultValue = "1") int cantidad,
            @RequestParam(required = false) String origen,
            HttpSession session,
            RedirectAttributes aviso) {

        try {
            String mensaje = carrito.agregar(session, productoId, cantidad);
            aviso.addFlashAttribute(EXITO, mensaje);
        } catch (IllegalArgumentException e) {
            aviso.addFlashAttribute(ERROR, e.getMessage());
        }

        return redirigirSegunOrigen(origen);
    }

    @PostMapping("/items/{productoId}/cantidad")
    public String actualizarCantidad(
            @PathVariable Long productoId,
            @RequestParam int cantidad,
            HttpSession session,
            RedirectAttributes aviso) {

        try {
            String mensaje = carrito.actualizarCantidad(session, productoId, cantidad);
            if (mensaje != null && !mensaje.isBlank()) {
                aviso.addFlashAttribute(EXITO, mensaje);
            }
        } catch (IllegalArgumentException e) {
            aviso.addFlashAttribute(ERROR, e.getMessage());
        }

        return REDIRECT_CARRITO;
    }

    @PostMapping("/items/{productoId}/eliminar")
    public String eliminar(
            @PathVariable Long productoId,
            HttpSession session,
            RedirectAttributes aviso) {

        carrito.eliminar(session, productoId);
        aviso.addFlashAttribute(EXITO, "Producto retirado del carrito.");
        return REDIRECT_CARRITO;
    }

    @PostMapping("/vaciar")
    public String vaciar(
            HttpSession session,
            RedirectAttributes aviso) {

        carrito.vaciar(session);
        aviso.addFlashAttribute(EXITO, "Se vació el carrito.");
        return REDIRECT_CARRITO;
    }

    @PostMapping("/sincronizar")
    public String sincronizar(
            @RequestParam(defaultValue = "[]") String itemsJson,
            HttpSession session) {

        carrito.sincronizarDesdeLocal(session, itemsJson);
        return REDIRECT_CARRITO;
    }

    @PostMapping("/confirmar")
    public String confirmar(
            @Valid @ModelAttribute("entrega") DatosEntregaForm entrega,
            BindingResult errores,
            HttpSession session,
            Authentication authentication,
            Model model,
            RedirectAttributes aviso) {

        ResumenCarrito resumen = carrito.obtenerResumen(session);
        if (resumen.isVacio()) {
            aviso.addFlashAttribute(
                    ERROR,
                    "Tu carrito está vacío. Agrega productos antes de confirmar el pedido."
            );
            return REDIRECT_CARRITO;
        }

        if (errores.hasErrors()) {
            carrito.guardarDatosEntrega(session, entrega);
            model.addAttribute("resumen", resumen);
            model.addAttribute("carritoCantidad", resumen.totalUnidades());
            model.addAttribute("entregaAutocompletada", false);
            return "cart/index";
        }

        try {
            PedidoConfirmado pedido = carrito.confirmarPedido(
                    session,
                    usuarioId(authentication),
                    nombreUsuario(authentication),
                    entrega
            );
            aviso.addFlashAttribute(
                    EXITO,
                    "Resumen del pedido " + pedido.codigo() + " confirmado y listo para el pago."
            );
            return "redirect:/carrito/confirmado";
        } catch (IllegalStateException e) {
            aviso.addFlashAttribute(ERROR, e.getMessage());
            return REDIRECT_CARRITO;
        }
    }

    @GetMapping("/confirmado")
    public String verConfirmado(
            HttpSession session,
            HttpServletRequest request,
            Model model,
            RedirectAttributes aviso) {

        PedidoConfirmado pedido = carrito.obtenerUltimoPedido(session);
        if (pedido == null) {
            aviso.addFlashAttribute(
                    ERROR,
                    "No hay ningún pedido pendiente de pago. Revisa tu carrito."
            );
            return REDIRECT_CARRITO;
        }

        model.addAttribute("pedido", pedido);
        model.addAttribute("carritoCantidad", carrito.contarUnidades(session));
        return "cart/confirmed";
    }

    @PostMapping("/restaurar")
    public String restaurarPedido(
            HttpSession session,
            RedirectAttributes aviso) {

        carrito.restaurarPedidoAlCarrito(session);
        aviso.addFlashAttribute(
                EXITO,
                "Los productos volvieron a tu carrito para que puedas editarlos."
        );
        return REDIRECT_CARRITO;
    }

    private UUID usuarioId(Authentication authentication) {
        if (authentication != null
                && authentication.getPrincipal() instanceof CestaPrincipal principal) {
            return principal.id();
        }
        return null;
    }

    private String nombreUsuario(Authentication authentication) {
        if (authentication != null
                && authentication.getPrincipal() instanceof CestaPrincipal principal) {
            return principal.displayName();
        }
        if (authentication != null && authentication.getName() != null) {
            return authentication.getName();
        }
        return "Cliente La Cesta";
    }

    private String redirigirSegunOrigen(String origen) {
        if (origen != null && origen.startsWith("/") && !origen.startsWith("//")) {
            return "redirect:" + origen;
        }
        return REDIRECT_CARRITO;
    }
}
