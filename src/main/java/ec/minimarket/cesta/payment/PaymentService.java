package ec.minimarket.cesta.payment;

import ec.minimarket.cesta.cart.CarritoItem;
import ec.minimarket.cesta.cart.CarritoService;
import ec.minimarket.cesta.cart.DatosEntregaForm;
import ec.minimarket.cesta.cart.Pedido;
import ec.minimarket.cesta.cart.PedidoDetalle;
import ec.minimarket.cesta.cart.PedidoRepository;
import ec.minimarket.cesta.cart.ResumenCarrito;
import ec.minimarket.cesta.catalog.ProductoAgotado;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class PaymentService {

    private static final int PEDIDOS_POR_PAGINA = 5;
    private static final String ATRIBUTO_INTENTO = "LA_CESTA_PAYPHONE_INTENTO";

    private static final DateTimeFormatter FORMATO_HORA =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
                    .withZone(ZoneId.of("America/Guayaquil"));

    private final PedidoRepository pedidos;
    private final CarritoService carritoService;
    private final PaymentProductRepository productRepository;
    private final PaymentOrderRepository orderRepository;
    private final PayPhoneGatewayProperties config;
    private final PayPhoneClient payPhoneClient;
    private final ApplicationEventPublisher eventos;
    private final JdbcTemplate jdbc;
    private final Validator validator;
    private final Map<String, PaymentResult> resultadosRecientes = new ConcurrentHashMap<>();

    public PaymentService(
            PedidoRepository pedidos,
            CarritoService carritoService,
            PaymentProductRepository productRepository,
            PaymentOrderRepository orderRepository,
            PayPhoneGatewayProperties config,
            PayPhoneClient payPhoneClient,
            ApplicationEventPublisher eventos,
            JdbcTemplate jdbc,
            Validator validator) {
        this.pedidos = pedidos;
        this.carritoService = carritoService;
        this.productRepository = productRepository;
        this.orderRepository = orderRepository;
        this.config = config;
        this.payPhoneClient = payPhoneClient;
        this.eventos = eventos;
        this.jdbc = jdbc;
        this.validator = validator;
    }

    public Page<Pedido> listarHistorialCompras(UUID usuarioId, int pagina) {
        var solicitud = PageRequest.of(Math.max(0, pagina), PEDIDOS_POR_PAGINA);
        if (usuarioId == null || solicitud.getOffset() > Integer.MAX_VALUE) {
            return Page.empty(solicitud);
        }

        // Paginamos los pedidos antes de cargar sus detalles para conservar el límite en SQL.
        Page<Long> ids = orderRepository.buscarIdsDelHistorial(usuarioId, solicitud);
        if (ids.isEmpty()) {
            return new PageImpl<>(List.of(), solicitud, ids.getTotalElements());
        }
        List<Pedido> compras = orderRepository.findByUsuarioIdAndIdInOrderByCreadoEnDescIdDesc(
                usuarioId, ids.getContent());
        return new PageImpl<>(compras, solicitud, ids.getTotalElements());
    }

    public boolean isLiveConfigured() {
        return config.isLiveConfigured();
    }

    public ResumenCarrito obtenerResumenCarrito(UUID usuarioId) {
        if (usuarioId == null) {
            return new ResumenCarrito(
                    List.of(), 0, 0,
                    BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2));
        }
        return carritoService.obtenerResumen(usuarioId);
    }

    public void guardarDatosEntrega(HttpSession session, DatosEntregaForm entrega) {
        if (session != null && entrega != null) {
            carritoService.guardarDatosEntrega(session, entrega);
        }
    }

    public void guardarIntentoCompra(HttpSession session, UUID intentoCompra) {
        if (session != null && intentoCompra != null) {
            session.setAttribute(ATRIBUTO_INTENTO, intentoCompra);
        }
    }

    public UUID obtenerIntentoCompra(HttpSession session, UUID usuarioId) {
        if (usuarioId != null) {
            List<UUID> intentos = jdbc.query(
                    "select intento_compra from la_cesta.carritos where usuario_id = ?",
                    (rs, rowNum) -> rs.getObject(1, UUID.class),
                    usuarioId
            );
            if (!intentos.isEmpty() && intentos.get(0) != null) {
                return intentos.get(0);
            }
        }
        if (session != null && session.getAttribute(ATRIBUTO_INTENTO) instanceof UUID id) {
            return id;
        }
        return null;
    }

    public PaymentSummary prepararResumenPorPedido(
            Long pedidoId,
            UUID usuarioId,
            String nombreUsuario,
            boolean isAdmin) {

        Pedido pedido = cargarPedidoConDetalles(pedidoId);
        validarPropietario(pedido, usuarioId, isAdmin);
        return mapearPedido(pedido, nombreUsuario);
    }

    public PaymentSummary prepararResumenGeneral(
            HttpSession session,
            UUID usuarioId,
            String nombreUsuario) {

        if (usuarioId != null) {
            ResumenCarrito carrito = carritoService.obtenerResumen(usuarioId);
            if (!carrito.isVacio()) {
                DatosEntregaForm entrega = obtenerEntregaSegura(session, usuarioId);
                return mapearCarrito(carrito, entrega, nombreUsuario);
            }
            Pedido ultimo = pedidos.findFirstByUsuarioIdOrderByCreadoEnDesc(usuarioId).orElse(null);
            if (ultimo != null) {
                Pedido conDetalles = pedidos.findWithDetallesByIdAndUsuarioId(ultimo.getId(), usuarioId)
                        .orElse(ultimo);
                return mapearPedido(conDetalles, nombreUsuario);
            }
        }
        return resumenDemostrativo(nombreUsuario);
    }

    public String iniciarPagoPayPhone(
            Long pedidoId,
            HttpSession session,
            UUID usuarioId,
            String nombreUsuario,
            boolean isAdmin) {

        PaymentSummary summary = pedidoId != null
                ? prepararResumenPorPedido(pedidoId, usuarioId, nombreUsuario, isAdmin)
                : prepararResumenGeneral(session, usuarioId, nombreUsuario);

        String randomTx = UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        String clientTxId = summary.referencia() + "-TX" + randomTx;
        if (config.isLiveConfigured()) {
            PayPhoneClient.PrepareResponse prepared = payPhoneClient.prepare(summary, clientTxId);
            return prepared.payWithPayPhone();
        }
        return construirRedireccionLocalPayPhone(pedidoId, clientTxId);
    }

    @Transactional
    public PaymentResult procesarConfirmacionReal(
            Long transactionId,
            String clientTxId,
            boolean cancelado,
            Long pedidoId,
            HttpSession session,
            UUID usuarioId,
            String nombreUsuario,
            boolean isAdmin) {

        PaymentSummary summary = obtenerResumenSeguro(
                pedidoId, clientTxId, session, usuarioId, nombreUsuario, isAdmin);
        String txSeguro = (clientTxId == null || clientTxId.isBlank()) ? summary.referencia() : clientTxId.strip();

        PaymentResult previo = resultadosRecientes.get(txSeguro);
        if (previo != null && previo.aprobado()) {
            return previo;
        }

        if (cancelado || transactionId == null || transactionId <= 0) {
            PaymentResult resultado = new PaymentResult(
                    "CANCELADO",
                    false,
                    "—",
                    transactionId == null ? "—" : transactionId.toString(),
                    summary.referencia(),
                    txSeguro,
                    summary.pedidoId(),
                    summary.total(),
                    summary.moneda(),
                    "El pago fue cancelado antes de completar la autorización en PayPhone.",
                    FORMATO_HORA.format(Instant.now()),
                    config.isLiveConfigured()
            );
            guardarResultado(resultado);
            return resultado;
        }

        if (!config.isLiveConfigured()) {
            return procesarYAplicarDescuento(summary, session, usuarioId, txSeguro, true);
        }

        PayPhoneClient.ConfirmResponse confirm = payPhoneClient.confirm(transactionId, txSeguro);
        boolean aprobado = confirm.isApproved();
        BigDecimal monto = confirm.amount() > 0
                ? BigDecimal.valueOf(confirm.amount(), 2)
                : summary.total();

        Long pedidoFinalId = summary.pedidoId();
        String referenciaFinal = summary.referencia();
        if (aprobado) {
            Pedido confirmado = finalizarCompraYDescontar(summary.pedidoId(), session, usuarioId);
            if (confirmado != null) {
                pedidoFinalId = confirmado.getId();
                referenciaFinal = config.getReferencePrefix() + String.format("%05d", confirmado.getId());
            }
        }

        PaymentResult resultado = new PaymentResult(
                aprobado ? "APROBADO" : "RECHAZADO",
                aprobado,
                confirm.authorizationCode() == null || confirm.authorizationCode().isBlank()
                        ? "—" : confirm.authorizationCode(),
                String.valueOf(confirm.transactionId()),
                referenciaFinal,
                txSeguro,
                pedidoFinalId,
                monto,
                summary.moneda(),
                aprobado
                        ? "Pago confirmado por PayPhone, pedido registrado y stock actualizado."
                        : (confirm.message() == null || confirm.message().isBlank()
                                ? "PayPhone no aprobó la transacción."
                                : confirm.message()),
                FORMATO_HORA.format(Instant.now()),
                true
        );
        guardarResultado(resultado);
        return resultado;
    }

    @Transactional
    public PaymentResult procesarSimulacionDemo(
            Long pedidoId,
            String clientTxId,
            boolean aprobado,
            HttpSession session,
            UUID usuarioId,
            String nombreUsuario,
            boolean isAdmin) {

        String txSeguroInput = (clientTxId == null || clientTxId.isBlank()) ? null : clientTxId.strip();
        if (txSeguroInput != null) {
            PaymentResult previo = resultadosRecientes.get(txSeguroInput);
            if (previo != null && previo.aprobado()) {
                return previo;
            }
        }

        PaymentSummary summary = pedidoId != null
                ? prepararResumenPorPedido(pedidoId, usuarioId, nombreUsuario, isAdmin)
                : prepararResumenGeneral(session, usuarioId, nombreUsuario);

        String txSeguro = txSeguroInput == null ? summary.referencia() + "-DEMO" : txSeguroInput;
        return procesarYAplicarDescuento(summary, session, usuarioId, txSeguro, aprobado);
    }

    public PaymentResult consultarResultadoReciente(String clientTxId) {
        if (clientTxId == null || clientTxId.isBlank()) {
            return null;
        }
        return resultadosRecientes.get(clientTxId.strip());
    }

    public String construirRedireccionLocalPayPhone(Long pedidoId, String clientTxId) {
        StringBuilder url = new StringBuilder("/pagos/payphone");
        boolean tieneParam = false;
        if (pedidoId != null) {
            url.append("?pedidoId=").append(pedidoId);
            tieneParam = true;
        }
        if (clientTxId != null && !clientTxId.isBlank()) {
            url.append(tieneParam ? "&" : "?").append("tx=").append(clientTxId.strip());
        }
        return url.toString();
    }

    private PaymentResult procesarYAplicarDescuento(
            PaymentSummary summary,
            HttpSession session,
            UUID usuarioId,
            String txSeguro,
            boolean aprobado) {

        Long pedidoFinalId = summary.pedidoId();
        String referenciaFinal = summary.referencia();

        if (aprobado) {
            Pedido confirmado = finalizarCompraYDescontar(summary.pedidoId(), session, usuarioId);
            if (confirmado != null) {
                pedidoFinalId = confirmado.getId();
                referenciaFinal = config.getReferencePrefix() + String.format("%05d", confirmado.getId());
            }
        }

        String sufijo = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        long randomPayphoneId = 100_000_000L + ThreadLocalRandom.current().nextLong(900_000_000L);
        PaymentResult resultado = new PaymentResult(
                aprobado ? "APROBADO" : "CANCELADO",
                aprobado,
                aprobado ? "AUTH-" + sufijo : "—",
                aprobado ? String.valueOf(randomPayphoneId) : "—",
                referenciaFinal,
                txSeguro,
                pedidoFinalId,
                summary.total(),
                summary.moneda(),
                aprobado
                        ? "Autorización simulada correctamente. El pedido quedó registrado y el stock fue descontado en la base de datos."
                        : "Transacción cancelada por el usuario en la interfaz de PayPhone.",
                FORMATO_HORA.format(Instant.now()),
                false
        );
        guardarResultado(resultado);
        return resultado;
    }

    private Pedido finalizarCompraYDescontar(Long pedidoId, HttpSession session, UUID usuarioId) {
        if (pedidoId == null) {
            if (usuarioId == null) {
                return null;
            }
            ResumenCarrito carrito = carritoService.obtenerResumen(usuarioId);
            if (carrito.isVacio()) {
                return null;
            }

            DatosEntregaForm entrega = obtenerEntregaSegura(session, usuarioId);
            UUID intento = obtenerIntentoCompra(session, usuarioId);
            if (tieneEntregaValida(entrega) && intento != null) {
                Pedido confirmado = carritoService.confirmarPedido(usuarioId, intento, entrega);
                if (confirmado == null) {
                    throw new IllegalStateException(
                            "Cambió el stock disponible. Revisa tu carrito antes de continuar."
                    );
                }
                return confirmado;
            }

            aplicarDescuentoStock(recopilarDescuentosCarrito(carrito));
            carritoService.vaciar(usuarioId);
            return null;
        }

        Pedido existente = cargarPedidoConDetalles(pedidoId);
        if (existente.getIntentoCompra() == null) {
            aplicarDescuentoStock(recopilarDescuentosPedido(existente));
        }
        return null;
    }

    private DatosEntregaForm obtenerEntregaSegura(HttpSession session, UUID usuarioId) {
        if (session != null) {
            return carritoService.obtenerDatosEntrega(session, usuarioId);
        }
        if (usuarioId != null) {
            Pedido ultimo = pedidos.findFirstByUsuarioIdOrderByCreadoEnDesc(usuarioId).orElse(null);
            if (ultimo != null) {
                return new DatosEntregaForm(
                        ultimo.getDireccionEntrega(),
                        ultimo.getTelefonoContacto(),
                        ultimo.getReferenciaEntrega() == null ? "" : ultimo.getReferenciaEntrega()
                );
            }
        }
        return DatosEntregaForm.vacio();
    }

    private void aplicarDescuentoStock(List<StockDescuento> descuentos) {
        for (StockDescuento item : descuentos) {
            int actualizados = productRepository.descontarStock(item.productoId(), item.cantidad());
            if (actualizados == 0) {
                throw new IllegalStateException(
                        "No hay stock suficiente para completar el pago de «" + item.nombre() + "»."
                );
            }
            if (item.stockAnterior() > 0 && item.stockAnterior() - item.cantidad() == 0) {
                eventos.publishEvent(new ProductoAgotado(item.productoId(), item.nombre()));
            }
        }
    }

    private boolean tieneEntregaValida(DatosEntregaForm entrega) {
        return entrega != null && validator.validate(entrega).isEmpty();
    }

    private List<StockDescuento> recopilarDescuentosPedido(Pedido pedido) {
        return pedido.getDetalles().stream()
                .map(detalle -> new StockDescuento(
                        detalle.getProducto().getId(),
                        detalle.getProducto().getNombre(),
                        detalle.getProducto().getStock(),
                        detalle.getCantidad()))
                .toList();
    }

    private List<StockDescuento> recopilarDescuentosCarrito(ResumenCarrito carrito) {
        return carrito.items().stream()
                .map(item -> new StockDescuento(
                        item.producto().getId(),
                        item.producto().getNombre(),
                        item.producto().getStock(),
                        item.cantidad()))
                .toList();
    }

    private record StockDescuento(Long productoId, String nombre, int stockAnterior, int cantidad) {
    }

    private Pedido cargarPedidoConDetalles(Long pedidoId) {
        Pedido pedido = pedidos.findById(pedidoId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "No se encontró el pedido solicitado para el pago."
                ));
        if (pedido.getUsuarioId() != null) {
            return pedidos.findWithDetallesByIdAndUsuarioId(pedidoId, pedido.getUsuarioId()).orElse(pedido);
        }
        return pedido;
    }

    private void guardarResultado(PaymentResult resultado) {
        if (resultadosRecientes.size() > 200) {
            resultadosRecientes.clear();
        }
        resultadosRecientes.put(resultado.clientTransactionId(), resultado);
    }

    private PaymentSummary obtenerResumenSeguro(
            Long pedidoId,
            String clientTxId,
            HttpSession session,
            UUID usuarioId,
            String nombreUsuario,
            boolean isAdmin) {

        Long idInferido = pedidoId != null ? pedidoId : extraerPedidoId(clientTxId);
        if (idInferido != null) {
            return prepararResumenPorPedido(idInferido, usuarioId, nombreUsuario, isAdmin);
        }
        return prepararResumenGeneral(session, usuarioId, nombreUsuario);
    }

    private Long extraerPedidoId(String clientTxId) {
        if (clientTxId == null || clientTxId.isBlank()) {
            return null;
        }
        String prefix = config.getReferencePrefix();
        if (!clientTxId.startsWith(prefix)) {
            return null;
        }
        String resto = clientTxId.substring(prefix.length());
        int guion = resto.indexOf('-');
        String numero = guion > 0 ? resto.substring(0, guion) : resto;
        try {
            return Long.valueOf(numero);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private void validarPropietario(Pedido pedido, UUID usuarioId, boolean isAdmin) {
        if (isAdmin || pedido.getUsuarioId() == null) {
            return;
        }
        if (!pedido.getUsuarioId().equals(usuarioId)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "No tienes autorización para pagar este pedido."
            );
        }
    }

    private PaymentSummary mapearCarrito(
            ResumenCarrito carrito,
            DatosEntregaForm entrega,
            String nombreUsuario) {

        List<PaymentSummary.PaymentLine> lineas = carrito.items().stream()
                .map(this::mapearItemCarrito)
                .toList();

        BigDecimal total = normalizarMonto(carrito.totalPagar());
        String telefono = (entrega == null || entrega.telefono().isBlank()) ? "Por registrar" : entrega.telefono();
        String direccion = (entrega == null || entrega.direccion().isBlank())
                ? "Entrega en tienda / por confirmar"
                : entrega.direccion();

        return new PaymentSummary(
                null,
                "LC-CARRITO",
                config.getStoreName(),
                nombreUsuario,
                telefono,
                direccion,
                normalizarMonto(carrito.subtotalRegular()),
                normalizarMonto(carrito.descuentoPromociones()),
                total,
                convertirACentavos(total),
                config.getCurrency(),
                carrito.totalUnidades(),
                "Carrito activo",
                false,
                lineas
        );
    }

    private PaymentSummary.PaymentLine mapearItemCarrito(CarritoItem item) {
        String categoria = (item.producto().getCategoria() != null) ? item.producto().getCategoria().getNombre() : "General";
        return new PaymentSummary.PaymentLine(
                item.producto().getNombre(),
                item.cantidad(),
                normalizarMonto(item.producto().getPrecioFinal()),
                normalizarMonto(item.getSubtotalFinal()),
                item.producto().getId(),
                item.producto().getImagenRevision(),
                categoria
        );
    }

    private PaymentSummary mapearPedido(Pedido pedido, String nombreUsuario) {
        List<PaymentSummary.PaymentLine> lineas = pedido.getDetalles().stream()
                .map(this::mapearDetalle)
                .toList();

        BigDecimal total = normalizarMonto(pedido.getTotal());
        String referencia = config.getReferencePrefix() + String.format("%05d", pedido.getId());

        return new PaymentSummary(
                pedido.getId(),
                referencia,
                config.getStoreName(),
                nombreUsuario,
                pedido.getTelefonoContacto(),
                pedido.getDireccionEntrega(),
                normalizarMonto(pedido.getSubtotal()),
                normalizarMonto(pedido.getDescuentoTotal()),
                total,
                convertirACentavos(total),
                config.getCurrency(),
                pedido.getTotalUnidades(),
                pedido.getFechaFormateada(),
                true,
                lineas
        );
    }

    private PaymentSummary.PaymentLine mapearDetalle(PedidoDetalle detalle) {
        String categoria = (detalle.getProducto().getCategoria() != null) ? detalle.getProducto().getCategoria().getNombre() : "General";
        return new PaymentSummary.PaymentLine(
                detalle.getProducto().getNombre(),
                detalle.getCantidad(),
                normalizarMonto(detalle.getPrecioUnitario()),
                normalizarMonto(detalle.getSubtotal()),
                detalle.getProducto().getId(),
                detalle.getProducto().getImagenRevision(),
                categoria
        );
    }

    private PaymentSummary resumenDemostrativo(String nombreUsuario) {
        BigDecimal totalDemo = new BigDecimal("12.50");
        return new PaymentSummary(
                null,
                "LC-DEMO-00001",
                config.getStoreName(),
                nombreUsuario,
                "0999999999",
                "Quito · Pedido de demostración",
                totalDemo,
                BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                totalDemo,
                convertirACentavos(totalDemo),
                config.getCurrency(),
                1,
                "Demostración PayPhone",
                false,
                List.of(new PaymentSummary.PaymentLine(
                        "Canasta de prueba La Cesta",
                        1,
                        totalDemo,
                        totalDemo
                ))
        );
    }

    private static BigDecimal normalizarMonto(BigDecimal monto) {
        if (monto == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return monto.setScale(2, RoundingMode.HALF_UP);
    }

    private static long convertirACentavos(BigDecimal monto) {
        return normalizarMonto(monto).movePointRight(2).longValueExact();
    }
}
