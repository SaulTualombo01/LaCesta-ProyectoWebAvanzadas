package ec.minimarket.cesta.cart;

import ec.minimarket.cesta.catalog.Producto;
import ec.minimarket.cesta.catalog.ProductoRepository;
import jakarta.servlet.http.HttpSession;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class CarritoService {

    static final String ATRIBUTO_CARRITO = "LA_CESTA_CARRITO";
    static final String ATRIBUTO_ENTREGA = "LA_CESTA_DATOS_ENTREGA";

    private static final Pattern PAR_JSON = Pattern.compile(
            "\"id\"\\s*:\\s*(\\d+)\\s*,\\s*\"cantidad\"\\s*:\\s*(\\d+)"
    );

    private final ProductoRepository productos;
    private final PedidoRepository pedidos;

    public CarritoService(ProductoRepository productos, PedidoRepository pedidos) {
        this.productos = productos;
        this.pedidos = pedidos;
    }

    public ResumenCarrito obtenerResumen(HttpSession session) {
        Map<Long, Integer> mapa = obtenerMapa(session, false);
        if (mapa == null || mapa.isEmpty()) {
            return new ResumenCarrito(
                    List.of(),
                    0,
                    0,
                    BigDecimal.ZERO.setScale(2),
                    BigDecimal.ZERO.setScale(2),
                    BigDecimal.ZERO.setScale(2)
            );
        }

        List<CarritoItem> items = new ArrayList<>();
        int totalUnidades = 0;
        BigDecimal subtotalRegular = BigDecimal.ZERO.setScale(2);
        BigDecimal descuentoPromociones = BigDecimal.ZERO.setScale(2);
        BigDecimal totalPagar = BigDecimal.ZERO.setScale(2);

        List<Long> paraEliminar = new ArrayList<>();

        for (Map.Entry<Long, Integer> entrada : mapa.entrySet()) {
            Long idProducto = entrada.getKey();
            int solicitada = entrada.getValue() == null ? 0 : entrada.getValue();
            if (solicitada <= 0) {
                paraEliminar.add(idProducto);
                continue;
            }

            Producto producto = productos.findById(idProducto).orElse(null);
            if (producto == null || producto.getStock() <= 0) {
                paraEliminar.add(idProducto);
                continue;
            }

            int cantidadEfectiva = Math.min(solicitada, producto.getStock());
            if (cantidadEfectiva != solicitada) {
                entrada.setValue(cantidadEfectiva);
            }

            CarritoItem item = new CarritoItem(producto, cantidadEfectiva);
            items.add(item);
            totalUnidades += cantidadEfectiva;
            subtotalRegular = subtotalRegular.add(item.getSubtotalRegular());
            descuentoPromociones = descuentoPromociones.add(item.getAhorroLinea());
            totalPagar = totalPagar.add(item.getSubtotalFinal());
        }

        for (Long id : paraEliminar) {
            mapa.remove(id);
        }

        return new ResumenCarrito(
                List.copyOf(items),
                totalUnidades,
                items.size(),
                subtotalRegular,
                descuentoPromociones,
                totalPagar
        );
    }

    public int contarUnidades(HttpSession session) {
        return obtenerResumen(session).totalUnidades();
    }

    public String agregar(HttpSession session, Long productoId, int cantidad) {
        if (cantidad <= 0) {
            throw new IllegalArgumentException("La cantidad a agregar debe ser al menos 1.");
        }

        Producto producto = consultarProductoDisponible(productoId);
        Map<Long, Integer> mapa = obtenerMapa(session, true);
        int actual = mapa.getOrDefault(productoId, 0);
        int nuevaCantidad = actual + cantidad;

        if (nuevaCantidad > producto.getStock()) {
            mapa.put(productoId, producto.getStock());
            return "Se ajustó «" + producto.getNombre()
                    + "» al stock máximo disponible (" + producto.getStock() + ").";
        }

        mapa.put(productoId, nuevaCantidad);
        return "Se agregó «" + producto.getNombre() + "» a tu carrito.";
    }

    public String actualizarCantidad(HttpSession session, Long productoId, int cantidad) {
        Map<Long, Integer> mapa = obtenerMapa(session, true);
        if (cantidad <= 0) {
            mapa.remove(productoId);
            return "Producto retirado del carrito.";
        }

        Producto producto = consultarProductoDisponible(productoId);
        if (cantidad > producto.getStock()) {
            mapa.put(productoId, producto.getStock());
            return "Solo hay " + producto.getStock() + " unidades disponibles de «"
                    + producto.getNombre() + "».";
        }

        mapa.put(productoId, cantidad);
        return null;
    }

    public void eliminar(HttpSession session, Long productoId) {
        Map<Long, Integer> mapa = obtenerMapa(session, false);
        if (mapa != null) {
            mapa.remove(productoId);
        }
    }

    public void vaciar(HttpSession session) {
        session.removeAttribute(ATRIBUTO_CARRITO);
    }

    public void sincronizarDesdeLocal(HttpSession session, String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        Map<Long, Integer> actual = obtenerMapa(session, false);
        if (actual != null && !actual.isEmpty()) {
            return;
        }
        Map<Long, Integer> mapa = obtenerMapa(session, true);
        Matcher matcher = PAR_JSON.matcher(json);
        int contador = 0;
        while (matcher.find() && contador < 40) {
            contador++;
            try {
                Long id = Long.valueOf(matcher.group(1));
                int cantidad = Integer.parseInt(matcher.group(2));
                if (cantidad > 0) {
                    Producto prod = productos.findById(id).orElse(null);
                    if (prod != null && prod.getStock() > 0) {
                        mapa.put(id, Math.min(cantidad, prod.getStock()));
                    }
                }
            } catch (NumberFormatException ignored) {
                // Ignora entradas malformadas del almacenamiento local.
            }
        }
    }

    public DatosEntregaForm obtenerDatosEntrega(HttpSession session, UUID usuarioId) {
        Object guardado = session.getAttribute(ATRIBUTO_ENTREGA);
        if (guardado instanceof DatosEntregaForm datos && !datos.direccion().isBlank()) {
            return datos;
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

    public void guardarDatosEntrega(HttpSession session, DatosEntregaForm entrega) {
        session.setAttribute(ATRIBUTO_ENTREGA, entrega.normalizado());
    }

    @Transactional
    public Pedido confirmarPedido(
            HttpSession session,
            UUID usuarioId,
            DatosEntregaForm entrega) {
        ResumenCarrito resumen = obtenerResumen(session);
        if (resumen.isVacio()) {
            throw new IllegalStateException("Tu carrito está vacío. Agrega productos antes de continuar.");
        }

        DatosEntregaForm normalizado = entrega.normalizado();
        guardarDatosEntrega(session, normalizado);

        Pedido pedido = new Pedido(
                usuarioId,
                normalizado,
                resumen,
                Instant.now()
        );

        for (CarritoItem item : resumen.items()) {
            pedido.agregarDetalle(
                    item.producto(),
                    item.cantidad(),
                    item.producto().getPrecioFinal()
            );
        }

        Pedido guardado = pedidos.save(pedido);
        vaciar(session);
        return guardado;
    }

    public Pedido consultarPedido(Long id) {
        return pedidos.findWithDetallesById(id).orElse(null);
    }

    @Transactional
    public void restaurarPedidoAlCarrito(HttpSession session, Long pedidoId) {
        Pedido pedido = pedidos.findWithDetallesById(pedidoId).orElse(null);
        if (pedido == null || pedido.getDetalles().isEmpty()) {
            return;
        }
        Map<Long, Integer> mapa = obtenerMapa(session, true);
        for (PedidoDetalle detalle : pedido.getDetalles()) {
            Producto prod = productos.findById(detalle.getProducto().getId()).orElse(null);
            if (prod != null && prod.getStock() > 0) {
                int acumulada = mapa.getOrDefault(prod.getId(), 0) + detalle.getCantidad();
                mapa.put(prod.getId(), Math.min(acumulada, prod.getStock()));
            }
        }
        pedidos.delete(pedido);
    }

    private Producto consultarProductoDisponible(Long id) {
        Producto producto = productos.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "El producto seleccionado no existe."
                ));
        if (producto.getStock() <= 0) {
            throw new IllegalArgumentException(
                    "El producto «" + producto.getNombre() + "» está agotado."
            );
        }
        return producto;
    }

    @SuppressWarnings("unchecked")
    private Map<Long, Integer> obtenerMapa(HttpSession session, boolean crear) {
        Object atributo = session.getAttribute(ATRIBUTO_CARRITO);
        if (atributo instanceof Map<?, ?> existente) {
            return (Map<Long, Integer>) existente;
        }
        if (!crear) {
            return null;
        }
        Map<Long, Integer> nuevo = new LinkedHashMap<>();
        session.setAttribute(ATRIBUTO_CARRITO, nuevo);
        return nuevo;
    }
}
