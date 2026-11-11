package ec.minimarket.cesta.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "producto_imagen")
class ProductoImagen {
    @Id
    @Column(name = "producto_id")
    private Long productoId;

    @Column(nullable = false, columnDefinition = "bytea")
    private byte[] contenido;

    protected ProductoImagen() {
    }

    ProductoImagen(Long productoId, byte[] contenido) {
        this.productoId = productoId;
        this.contenido = contenido;
    }

    byte[] contenido() {
        return contenido;
    }
}
