package ec.minimarket.cesta.payment;

import ec.minimarket.cesta.account.CestaPrincipal;
import ec.minimarket.cesta.cart.DatosEntregaForm;
import ec.minimarket.cesta.cart.Pedido;
import ec.minimarket.cesta.cart.ResumenCarrito;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.data.domain.Page;
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
@RequestMapping("/pagos")
public class PaymentController {

    private final PaymentService payments;

    public PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    @GetMapping
    public String checkoutGeneral(
            HttpSession session,
            Authentication authentication,
            Model model) {

        PaymentSummary resumen = payments.prepararResumenGeneral(
                session,
                usuarioId(authentication),
                nombreUsuario(authentication)
        );
        model.addAttribute("pago", resumen);
        model.addAttribute("modoReal", payments.isLiveConfigured());
        return "payment/checkout";
    }

    @PostMapping
    public String checkoutDesdeCarrito(
            @Valid @ModelAttribute("entrega") DatosEntregaForm entrega,
            BindingResult errores,
            @RequestParam(required = false) UUID intentoCompra,
            HttpSession session,
            Authentication authentication,
            Model model,
            RedirectAttributes aviso) {

        UUID usuario = usuarioId(authentication);
        ResumenCarrito resumenCarrito = payments.obtenerResumenCarrito(usuario);
        if (resumenCarrito.isVacio()) {
            aviso.addFlashAttribute("error", "Tu carrito está vacío. Agrega productos antes de pagar.");
            return "redirect:/carrito";
        }
        if (errores.hasErrors()) {
            UUID intento = intentoCompra != null ? intentoCompra : payments.obtenerIntentoCompra(session, usuario);
            payments.guardarDatosEntrega(session, entrega);
            payments.guardarIntentoCompra(session, intento);
            model.addAttribute("intentoCompra", intento);
            model.addAttribute("resumen", resumenCarrito);
            model.addAttribute("carritoCantidad", resumenCarrito.totalUnidades());
            model.addAttribute("entregaAutocompletada", false);
            return "cart/index";
        }
        payments.guardarDatosEntrega(session, entrega);
        payments.guardarIntentoCompra(session, intentoCompra);
        return "redirect:/pagos";
    }

    @GetMapping("/pedido/{pedidoId}")
    public String checkoutPedido(
            @PathVariable Long pedidoId,
            Authentication authentication,
            Model model) {

        PaymentSummary resumen = payments.prepararResumenPorPedido(
                pedidoId,
                usuarioId(authentication),
                nombreUsuario(authentication),
                esAdmin(authentication)
        );
        model.addAttribute("pago", resumen);
        model.addAttribute("modoReal", payments.isLiveConfigured());
        return "payment/checkout";
    }

    @PostMapping("/payphone/iniciar")
    public String iniciarPayPhone(
            @RequestParam(required = false) Long pedidoId,
            HttpSession session,
            Authentication authentication,
            RedirectAttributes aviso) {

        try {
            String destino = payments.iniciarPagoPayPhone(
                    pedidoId,
                    session,
                    usuarioId(authentication),
                    nombreUsuario(authentication),
                    esAdmin(authentication)
            );
            return "redirect:" + destino;
        } catch (PayPhoneClient.PayPhoneException | IllegalStateException ex) {
            aviso.addFlashAttribute("error", ex.getMessage());
            return pedidoId != null ? "redirect:/pagos/pedido/" + pedidoId : "redirect:/pagos";
        }
    }

    @GetMapping("/payphone")
    public String interfazPayPhone(
            @RequestParam(required = false) Long pedidoId,
            @RequestParam(required = false) @Size(max = 256) String tx,
            HttpSession session,
            Authentication authentication,
            Model model) {

        PaymentSummary resumen = pedidoId != null
                ? payments.prepararResumenPorPedido(
                        pedidoId,
                        usuarioId(authentication),
                        nombreUsuario(authentication),
                        esAdmin(authentication))
                : payments.prepararResumenGeneral(
                        session,
                        usuarioId(authentication),
                        nombreUsuario(authentication));

        String randomSuffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        String txActual = (tx == null || tx.isBlank()) ? resumen.referencia() + "-TX" + randomSuffix : tx.strip();
        model.addAttribute("pago", resumen);
        model.addAttribute("tx", txActual);
        model.addAttribute("modoReal", payments.isLiveConfigured());
        return "payment/payphone";
    }

    @PostMapping("/payphone/simular")
    public String simularRespuestaPayPhone(
            @RequestParam(required = false) Long pedidoId,
            @RequestParam(required = false) @Size(max = 256) String tx,
            @RequestParam(defaultValue = "aprobado") @Pattern(regexp = "(?i:aprobado|cancelado)") String estado,
            HttpSession session,
            Authentication authentication,
            RedirectAttributes redirect) {

        boolean aprobado = "aprobado".equalsIgnoreCase(estado);
        try {
            PaymentResult resultado = payments.procesarSimulacionDemo(
                    pedidoId,
                    tx,
                    aprobado,
                    session,
                    usuarioId(authentication),
                    nombreUsuario(authentication),
                    esAdmin(authentication)
            );
            redirect.addAttribute("tx", resultado.clientTransactionId());
            return "redirect:/pagos/resultado";
        } catch (IllegalStateException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
            return pedidoId != null ? "redirect:/pagos/pedido/" + pedidoId : "redirect:/pagos";
        }
    }

    @GetMapping("/payphone/retorno")
    public String retornoPayPhone(
            @RequestParam(required = false) Long id,
            @RequestParam(required = false) String clientTransactionId,
            @RequestParam(defaultValue = "false") boolean cancelado,
            @RequestParam(required = false) Long pedidoId,
            HttpSession session,
            Authentication authentication,
            RedirectAttributes redirect,
            Model model) {

        try {
            PaymentResult resultado = payments.procesarConfirmacionReal(
                    id,
                    clientTransactionId,
                    cancelado,
                    pedidoId,
                    session,
                    usuarioId(authentication),
                    nombreUsuario(authentication),
                    esAdmin(authentication)
            );
            redirect.addAttribute("tx", resultado.clientTransactionId());
            return "redirect:/pagos/resultado";
        } catch (PayPhoneClient.PayPhoneException | IllegalStateException ex) {
            model.addAttribute("error", ex.getMessage());
            PaymentSummary resumen = payments.prepararResumenGeneral(
                    session,
                    usuarioId(authentication),
                    nombreUsuario(authentication)
            );
            model.addAttribute("pago", resumen);
            model.addAttribute("modoReal", payments.isLiveConfigured());
            return "payment/checkout";
        }
    }

    @GetMapping("/resultado")
    public String resultadoPago(
            @RequestParam(required = false) @Size(max = 256) String tx,
            HttpSession session,
            Authentication authentication,
            Model model) {

        PaymentResult resultado = payments.consultarResultadoReciente(tx);
        if (resultado == null) {
            PaymentSummary resumen = payments.prepararResumenGeneral(
                    session,
                    usuarioId(authentication),
                    nombreUsuario(authentication)
            );
            resultado = new PaymentResult(
                    "PENDIENTE",
                    false,
                    "—",
                    "—",
                    resumen.referencia(),
                    resumen.referencia(),
                    resumen.pedidoId(),
                    resumen.total(),
                    resumen.moneda(),
                    "Aún no se registra una transacción finalizada para esta sesión.",
                    "—",
                    payments.isLiveConfigured()
            );
        }
        model.addAttribute("resultado", resultado);
        return "payment/result";
    }

    @GetMapping({"/historial", "/mis-compras"})
    public String historialCompras(
            @RequestParam(defaultValue = "0") int page,
            Authentication authentication,
            Model model) {

        Page<Pedido> pagina = payments.listarHistorialCompras(usuarioId(authentication), page);
        if (pagina.isEmpty() && page > 0) {
            return "redirect:/pagos/historial";
        }
        model.addAttribute("pagina", pagina);
        return "payment/history";
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

    private boolean esAdmin(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    }
}
