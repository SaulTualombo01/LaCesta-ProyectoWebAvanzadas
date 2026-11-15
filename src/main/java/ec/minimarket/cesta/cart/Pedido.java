package ec.minimarket.cesta.cart;

import ec.minimarket.cesta.catalog.Producto;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "pedidos")
public class Pedido {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "usuario_id")
    private UUID usuarioId;

    @Column(name = "direccion_entrega", nullable = false, length = 180)
    private String direccionEntrega;

    @Column(name = "telefono_contacto", nullable = false, length = 15)
    private String telefonoContacto;

    @Column(name = "referencia_entrega", length = 250)
    private String referenciaEntrega;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal subtotal;

    @Column(name = "descuento_total", nullable = false, precision = 10, scale = 2)
    private BigDecimal descuentoTotal;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal total;

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn;

    @OneToMany(mappedBy = "pedido", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PedidoDetalle> detalles = new ArrayList<>();

    protected Pedido() {
    }

    public Pedido(
            UUID usuarioId,
            DatosEntregaForm entrega,
            ResumenCarrito resumen,
            Instant creadoEn) {
        this.usuarioId = usuarioId;
        this.direccionEntrega = entrega.direccion();
        this.telefonoContacto = entrega.telefono();
        this.referenciaEntrega = entrega.referencia().isBlank() ? null : entrega.referencia();
        this.subtotal = resumen.subtotalRegular();
        this.descuentoTotal = resumen.descuentoPromociones();
        this.total = resumen.totalPagar();
        this.creadoEn = creadoEn;
    }

    public void agregarDetalle(Producto producto, int cantidad, BigDecimal precioUnitario) {
        this.detalles.add(new PedidoDetalle(this, producto, cantidad, precioUnitario));
    }

    public Long getId() {
        return id;
    }

    public UUID getUsuarioId() {
        return usuarioId;
    }

    public String getDireccionEntrega() {
        return direccionEntrega;
    }

    public String getTelefonoContacto() {
        return telefonoContacto;
    }

    public String getReferenciaEntrega() {
        return referenciaEntrega;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }

    public BigDecimal getDescuentoTotal() {
        return descuentoTotal;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public Instant getCreadoEn() {
        return creadoEn;
    }

    public List<PedidoDetalle> getDetalles() {
        return detalles;
    }

    public int getTotalUnidades() {
        int suma = 0;
        for (PedidoDetalle detalle : detalles) {
            suma += detalle.getCantidad();
        }
        return suma;
    }

    public boolean isTieneDescuentos() {
        return descuentoTotal != null && descuentoTotal.compareTo(BigDecimal.ZERO) > 0;
    }
}
