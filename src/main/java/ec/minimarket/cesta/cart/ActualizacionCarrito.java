package ec.minimarket.cesta.cart;

import java.math.BigDecimal;
import java.util.List;

public record ActualizacionCarrito(List<Linea> items, String mensaje) {

    static ActualizacionCarrito desde(List<CarritoItem> items, String mensaje) {
        return new ActualizacionCarrito(items.stream().map(item -> new Linea(
                item.producto().getId().toString(), item.cantidad(), item.producto().getStock(),
                item.producto().getPrecio(), item.producto().getPrecioFinal())).toList(), mensaje);
    }

    public record Linea(String id, int cantidad, int stock, BigDecimal precio, BigDecimal precioFinal) {}
}
