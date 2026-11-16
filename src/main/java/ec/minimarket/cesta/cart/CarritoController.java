package ec.minimarket.cesta.cart;

import ec.minimarket.cesta.account.CestaPrincipal;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class CarritoController {

    private static final String EXITO = "exito";
    private static final String ERROR = "error";
    private static final String REDIRECT_CARRITO = "redirect:/carrito";

    private final CarritoService carrito;

    public CarritoController(CarritoService carrito) {
        this.carrito = carrito;
    }

    @GetMapping("/carrito")
    public String verCarrito(
            HttpSession session,
            Authentication authentication,
            Model model) {

        var vista = carrito.obtenerVista(usuarioId(authentication));
        ResumenCarrito resumen = vista.resumen();
        model.addAttribute("intentoCompra", vista.intento());
        model.addAttribute("resumen", resumen);
        model.addAttribute("carritoCantidad", resumen.totalUnidades());

        if (!model.containsAttribute("entrega")) {
            DatosEntregaForm entregaGuardada = carrito.obtenerDatosEntrega(
                    session,
                    usuarioId(authentication)
            );
            model.addAttribute("entrega", entregaGuardada);
            model.addAttribute(
                    "entregaAutocompletada",
                    !entregaGuardada.direccion().isBlank()
            );
        }

        return "cart/index";
    }

    @PostMapping("/carrito/agregar")
    public String agregar(
            @RequestParam Long productoId,
            @RequestParam(defaultValue = "1") int cantidad,
            @RequestParam(required = false) String origen,
            Authentication authentication,
            RedirectAttributes aviso) {

        try {
            String mensaje = carrito.agregar(usuarioId(authentication), productoId, cantidad);
            aviso.addFlashAttribute(EXITO, mensaje);
        } catch (IllegalArgumentException e) {
            aviso.addFlashAttribute(ERROR, e.getMessage());
        }

        return redirigirSegunOrigen(origen);
    }

    @PostMapping(value = "/carrito/items/{productoId}/cantidad", params = "!ajax")
    public String actualizarCantidad(
            @PathVariable Long productoId, @RequestParam int cantidad,
            Authentication authentication, RedirectAttributes aviso) {
        var actualizacion = carrito.actualizarCantidad(usuarioId(authentication), productoId, cantidad);
        if (actualizacion.mensaje() != null) {
            aviso.addFlashAttribute(EXITO, actualizacion.mensaje());
        }
        return REDIRECT_CARRITO;
    }

    @ResponseBody
    @PostMapping(value = "/carrito/items/{productoId}/cantidad", params = "ajax")
    public ActualizacionCarrito actualizarCantidadAjax(
            @PathVariable Long productoId, @RequestParam int cantidad, Authentication authentication) {
        return carrito.actualizarCantidad(usuarioId(authentication), productoId, cantidad);
    }

    @PostMapping("/carrito/items/{productoId}/eliminar")
    public String eliminar(@PathVariable Long productoId, Authentication authentication, RedirectAttributes aviso) {
        carrito.eliminar(usuarioId(authentication), productoId);
        aviso.addFlashAttribute(EXITO, "Producto retirado del carrito.");
        return REDIRECT_CARRITO;
    }

    @PostMapping("/carrito/vaciar")
    public String vaciar(Authentication authentication, RedirectAttributes aviso) {
        carrito.vaciar(usuarioId(authentication));
        aviso.addFlashAttribute(EXITO, "Se vació el carrito.");
        return REDIRECT_CARRITO;
    }

    @PostMapping("/carrito/confirmar")
    public String confirmar(
            @Valid @ModelAttribute("entrega") DatosEntregaForm entrega,
            BindingResult errores,
            @RequestParam UUID intentoCompra,
            HttpSession session,
            Authentication authentication,
            Model model,
            RedirectAttributes aviso) {

        UUID usuario = usuarioId(authentication);
        if (errores.hasErrors()) {
            var vista = carrito.obtenerVista(usuario);
            if (vista.resumen().isVacio()) {
                return REDIRECT_CARRITO;
            }
            carrito.guardarDatosEntrega(session, entrega);
            model.addAttribute("resumen", vista.resumen());
            model.addAttribute("intentoCompra", vista.intento());
            model.addAttribute("carritoCantidad", vista.resumen().totalUnidades());
            model.addAttribute("entregaAutocompletada", false);
            return "cart/index";
        }

        try {
            Pedido pedido = carrito.confirmarPedido(usuario, intentoCompra, entrega);
            if (pedido == null) {
                carrito.guardarDatosEntrega(session, entrega);
                aviso.addFlashAttribute(ERROR, "Cambió el stock. Revisa las cantidades antes de confirmar.");
                return REDIRECT_CARRITO;
            }
            carrito.guardarDatosEntrega(session, entrega);
            aviso.addFlashAttribute(
                    EXITO,
                    "Pedido confirmado."
            );
            return "redirect:/carrito/confirmado/" + pedido.getId();
        } catch (IllegalStateException e) {
            aviso.addFlashAttribute(ERROR, e.getMessage());
            return REDIRECT_CARRITO;
        }
    }

    @GetMapping("/carrito/confirmado/{id}")
    public String verConfirmado(
            @PathVariable Long id,
            Authentication authentication,
            Model model,
            RedirectAttributes aviso) {

        Pedido pedido = carrito.consultarPedido(id, usuarioId(authentication));
        if (pedido == null) {
            aviso.addFlashAttribute(
                    ERROR,
                    "No se encontró el pedido solicitado."
            );
            return REDIRECT_CARRITO;
        }

        model.addAttribute("pedido", pedido);
        model.addAttribute("cliente", nombreUsuario(authentication));
        model.addAttribute("carritoCantidad", carrito.contarUnidades(usuarioId(authentication)));
        return "cart/confirmed";
    }

    @PostMapping("/carrito/pedidos/{id}/restaurar")
    public String restaurarPedido(
            @PathVariable Long id,
            Authentication authentication,
            RedirectAttributes aviso) {

        try {
            if (carrito.restaurarPedidoAlCarrito(usuarioId(authentication), id)) {
                aviso.addFlashAttribute(EXITO, "Los productos volvieron a tu carrito para que puedas editarlos.");
            } else {
                aviso.addFlashAttribute(ERROR, "Ese pedido no se puede volver a editar.");
            }
        } catch (IllegalStateException e) {
            aviso.addFlashAttribute(ERROR, e.getMessage());
        }
        return REDIRECT_CARRITO;
    }

    @GetMapping("/admin/pedidos")
    public String listarPedidosAdmin(Model model) {
        List<Pedido> lista = carrito.listarTodosLosPedidos();
        int totalArticulos = 0;
        BigDecimal montoTotal = BigDecimal.ZERO.setScale(2);

        for (Pedido p : lista) {
            totalArticulos += p.getTotalUnidades();
            montoTotal = montoTotal.add(p.getTotal());
        }

        model.addAttribute("pedidos", lista);
        model.addAttribute("totalArticulos", totalArticulos);
        model.addAttribute("montoTotal", montoTotal);
        return "admin/pedidos";
    }

    @GetMapping("/entregas")
    public String listarEntregas(Model model) {
        List<Pedido> lista = carrito.listarTodosLosPedidos();
        long enEspera = lista.stream().filter(p -> !p.isEntregado()).count();
        long entregados = lista.size() - enEspera;

        model.addAttribute("pedidos", lista);
        model.addAttribute("enEspera", enEspera);
        model.addAttribute("entregados", entregados);
        return "cart/entregas";
    }

    @PostMapping("/entregas/{id}/estado")
    public String cambiarEstadoEntrega(
            @PathVariable Long id,
            RedirectAttributes aviso) {
        try {
            Pedido actualizado = carrito.cambiarEstadoEntrega(id);
            String texto = actualizado.isEntregado()
                    ? "Pedido #" + id + " marcado como entregado."
                    : "Pedido #" + id + " devuelto a en espera.";
            aviso.addFlashAttribute(EXITO, texto);
        } catch (IllegalArgumentException e) {
            aviso.addFlashAttribute(ERROR, e.getMessage());
        }
        return "redirect:/entregas";
    }

    private UUID usuarioId(Authentication authentication) {
        if (authentication != null
                && authentication.getPrincipal() instanceof CestaPrincipal principal) {
            return principal.id();
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN);
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
