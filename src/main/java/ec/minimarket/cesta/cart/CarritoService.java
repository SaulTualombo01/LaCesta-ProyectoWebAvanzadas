package ec.minimarket.cesta.cart;

import ec.minimarket.cesta.catalog.Producto;
import ec.minimarket.cesta.catalog.ProductoAgotado;
import ec.minimarket.cesta.catalog.ProductoRepository;
import jakarta.servlet.http.HttpSession;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class CarritoService {

    static final String ATRIBUTO_ENTREGA = "LA_CESTA_DATOS_ENTREGA";

    private final ProductoRepository productos;
    private final PedidoRepository pedidos;
    private final CarritoGuardado guardado;

    private final ApplicationEventPublisher eventos;

    public CarritoService(ProductoRepository productos, PedidoRepository pedidos, CarritoGuardado guardado,
                          ApplicationEventPublisher eventos) {
        this.productos = productos;
        this.pedidos = pedidos;
        this.guardado = guardado;
        this.eventos = eventos;
    }

    record Vista(ResumenCarrito resumen, UUID intento) {}

    Vista obtenerVista(UUID usuarioId) {
        var estado = guardado.leer(usuarioId);
        return new Vista(resumir(estado.cantidades()), estado.intento());
    }

    public ResumenCarrito obtenerResumen(UUID usuarioId) {
        return obtenerVista(usuarioId).resumen();
    }

    private ResumenCarrito resumir(Map<Long, Integer> mapa) {
        return resumir(mapa, mapa.isEmpty() ? List.of() : productos.findByIdIn(mapa.keySet()));
    }

    private ResumenCarrito resumir(Map<Long, Integer> mapa, List<Producto> filas) {
        if (mapa.isEmpty()) {
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

        var disponibles = filas.stream()
                .collect(Collectors.toMap(Producto::getId, Function.identity()));
        for (Map.Entry<Long, Integer> entrada : mapa.entrySet()) {
            Producto producto = disponibles.get(entrada.getKey());
            if (producto == null || producto.getStock() <= 0) {
                continue;
            }

            int cantidadEfectiva = Math.min(entrada.getValue(), producto.getStock());

            CarritoItem item = new CarritoItem(producto, cantidadEfectiva);
            items.add(item);
            totalUnidades += cantidadEfectiva;
            subtotalRegular = subtotalRegular.add(item.getSubtotalRegular());
            descuentoPromociones = descuentoPromociones.add(item.getAhorroLinea());
            totalPagar = totalPagar.add(item.getSubtotalFinal());
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

    public int contarUnidades(UUID usuarioId) {
        return obtenerResumen(usuarioId).totalUnidades();
    }

    @Transactional
    public String agregar(UUID usuarioId, Long productoId, int cantidad) {
        if (cantidad <= 0) {
            throw new IllegalArgumentException("La cantidad a agregar debe ser al menos 1.");
        }
        var mapa = guardado.bloquear(usuarioId);
        Producto producto = consultarProductoDisponible(productoId);
        long solicitada = (long) mapa.getOrDefault(productoId, 0) + cantidad;
        mapa.put(productoId, (int) Math.min(solicitada, producto.getStock()));
        guardado.guardar(usuarioId, mapa);
        if (solicitada > producto.getStock()) {
            return "Se ajustó «" + producto.getNombre() + "» al stock disponible (" + producto.getStock() + ").";
        }
        return "Se agregó «" + producto.getNombre() + "» a tu carrito.";
    }

    @Transactional
    public ActualizacionCarrito actualizarCantidad(UUID usuarioId, Long productoId, int cantidad) {
        var mapa = guardado.bloquear(usuarioId);
        if (!mapa.containsKey(productoId)) {
            return ActualizacionCarrito.desde(resumir(mapa).items(), "El producto ya no está en tu carrito.");
        }

        var resumen = resumir(mapa);
        var item = resumen.items().stream().filter(linea -> linea.producto().getId().equals(productoId))
                .findFirst().orElse(null);
        String mensaje = null;
        if (item == null || cantidad <= 0) {
            mapa.remove(productoId);
            mensaje = item == null ? "El producto ya no está disponible." : "Producto retirado del carrito.";
        } else {
            int stock = item.producto().getStock();
            mapa.put(productoId, Math.min(cantidad, stock));
            if (cantidad > stock) {
                mensaje = "Solo hay " + stock + " unidades disponibles de «" + item.producto().getNombre() + "».";
            }
        }
        guardado.guardar(usuarioId, mapa);
        var items = resumen.items().stream()
                .filter(linea -> mapa.containsKey(linea.producto().getId()))
                .map(linea -> linea.producto().getId().equals(productoId)
                        ? new CarritoItem(linea.producto(), mapa.get(productoId)) : linea)
                .toList();
        return ActualizacionCarrito.desde(items, mensaje);
    }

    @Transactional
    public void eliminar(UUID usuarioId, Long productoId) {
        var mapa = guardado.bloquear(usuarioId);
        if (mapa.remove(productoId) != null) {
            guardado.guardar(usuarioId, mapa);
        }
    }

    @Transactional
    public void vaciar(UUID usuarioId) {
        guardado.bloquear(usuarioId);
        guardado.vaciar(usuarioId);
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
    public Pedido confirmarPedido(UUID usuarioId, UUID intentoCompra, DatosEntregaForm entrega) {
        var mapa = guardado.bloquear(usuarioId);
        var yaConfirmado = pedidos.findByUsuarioIdAndIntentoCompra(usuarioId, intentoCompra);
        if (yaConfirmado.isPresent()) {
            return yaConfirmado.get();
        }
        if (!intentoCompra.equals(guardado.intento(usuarioId)) || mapa.isEmpty()) {
            throw new IllegalStateException("El carrito cambió. Revisa los productos antes de continuar.");
        }

        var resumen = resumir(mapa, productos.bloquearStock(mapa.keySet()));
        var disponibles = new LinkedHashMap<Long, Integer>();
        for (var item : resumen.items()) {
            disponibles.put(item.producto().getId(), item.cantidad());
        }
        if (!mapa.equals(disponibles)) {
            guardado.guardar(usuarioId, disponibles);
            return null;
        }

        DatosEntregaForm normalizado = entrega.normalizado();
        Pedido pedido = new Pedido(usuarioId, intentoCompra, normalizado, resumen, Instant.now());
        for (CarritoItem item : resumen.items()) {
            Producto producto = item.producto();
            producto.descontarStock(item.cantidad());
            pedido.agregarDetalle(producto, item.cantidad(), producto.getPrecioFinal());
            if (producto.getStock() == 0) {
                eventos.publishEvent(new ProductoAgotado(producto.getId(), producto.getNombre()));
            }
        }
        Pedido confirmado = pedidos.save(pedido);
        guardado.vaciar(usuarioId);
        return confirmado;
    }

    public Pedido consultarPedido(Long id, UUID usuarioId) {
        return pedidos.findWithDetallesByIdAndUsuarioId(id, usuarioId).orElse(null);
    }

    public List<Pedido> listarTodosLosPedidos() {
        return pedidos.findAllByOrderByCreadoEnDescIdDesc();
    }

    @Transactional
    public Pedido cambiarEstadoEntrega(Long pedidoId) {
        Pedido pedido = pedidos.bloquear(pedidoId)
                .orElseThrow(() -> new IllegalArgumentException("El pedido seleccionado no existe."));
        pedido.alternarEstadoEntrega();
        return pedidos.save(pedido);
    }

    @Transactional
    public boolean restaurarPedidoAlCarrito(UUID usuarioId, Long pedidoId) {
        var mapa = guardado.bloquear(usuarioId);
        Pedido pedido = pedidos.bloquear(pedidoId).orElse(null);
        if (pedido == null || !usuarioId.equals(pedido.getUsuarioId())
                || pedido.isEntregado() || pedido.getDetalles().isEmpty()) {
            return false;
        }
        var ids = pedido.getDetalles().stream().map(detalle -> detalle.getProducto().getId()).toList();
        var bloqueados = productos.bloquearStock(ids).stream()
                .collect(Collectors.toMap(Producto::getId, Function.identity()));
        for (PedidoDetalle detalle : pedido.getDetalles()) {
            Producto producto = bloqueados.get(detalle.getProducto().getId());
            if (pedido.getIntentoCompra() != null) {
                producto.reponerStock(detalle.getCantidad());
            }
            if (producto.getStock() > 0) {
                long acumulada = (long) mapa.getOrDefault(producto.getId(), 0) + detalle.getCantidad();
                mapa.put(producto.getId(), (int) Math.min(acumulada, producto.getStock()));
            }
        }
        guardado.guardar(usuarioId, mapa);
        pedidos.delete(pedido);
        return true;
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


}
