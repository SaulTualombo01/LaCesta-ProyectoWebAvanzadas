package ec.minimarket.cesta.cart;

import ec.minimarket.cesta.catalog.Producto;
import java.math.BigDecimal;

public record CarritoItem(Producto producto, int cantidad) {

    public BigDecimal getSubtotalRegular() {
        return producto.getPrecio().multiply(BigDecimal.valueOf(cantidad));
    }

    public BigDecimal getSubtotalFinal() {
        return producto.getPrecioFinal().multiply(BigDecimal.valueOf(cantidad));
    }

    public BigDecimal getAhorroLinea() {
        return producto.getAhorro().multiply(BigDecimal.valueOf(cantidad));
    }

    public boolean isTieneOferta() {
        return producto.getPrecioPromocional() != null;
    }
}
