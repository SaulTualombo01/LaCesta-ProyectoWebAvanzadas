package ec.minimarket.cesta.catalog;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "productos")
public class Producto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String nombre;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Categoria categoria;

    @Column(nullable = false, length = 500)
    private String descripcion;

    @Column(nullable = false, precision = 8, scale = 2)
    private BigDecimal precio;

    @Column(nullable = false)
    private int stock;

    @Column(name = "precio_promocional", precision = 8, scale = 2)
    private BigDecimal precioPromocional;

    @Column(name = "imagen_revision")
    private UUID imagenRevision;

    @Version
    @Column(nullable = false)
    private Long version;

    protected Producto() {
    }

    Producto(ProductoForm form) {
        actualizar(form);
    }

    void actualizar(ProductoForm form) {
        nombre = form.nombre().strip();
        categoria = form.categoria();
        descripcion = form.descripcion().strip();
        precio = form.precio();
        stock = form.stock();
        precioPromocional = form.precioPromocional();
    }

    void cambiarImagen(boolean tieneImagen) {
        imagenRevision = tieneImagen ? UUID.randomUUID() : null;
    }

    public Long getId() {
        return id;
    }

    public String getNombre() {
        return nombre;
    }

    public Categoria getCategoria() {
        return categoria;
    }

    public String getDescripcion() {
        return descripcion;
    }

    public BigDecimal getPrecio() {
        return precio;
    }

    public int getStock() {
        return stock;
    }

    public BigDecimal getPrecioPromocional() {
        return precioPromocional;
    }

    public BigDecimal getPrecioFinal() {
        return precioPromocional == null ? precio : precioPromocional;
    }

    public BigDecimal getAhorro() {
        return precio.subtract(getPrecioFinal());
    }

    public UUID getImagenRevision() {
        return imagenRevision;
    }

    public Long getVersion() {
        return version;
    }
}
