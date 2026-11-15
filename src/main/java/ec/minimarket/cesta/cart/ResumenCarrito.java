package ec.minimarket.cesta.cart;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

public record ResumenCarrito(
        List<CarritoItem> items,
        int totalUnidades,
        int totalLineas,
        BigDecimal subtotalRegular,
        BigDecimal descuentoPromociones,
        BigDecimal totalPagar
) {

    public boolean isVacio() {
        return items == null || items.isEmpty();
    }

    public boolean isTieneDescuentos() {
        return descuentoPromociones != null
                && descuentoPromociones.compareTo(BigDecimal.ZERO) > 0;
    }

    public String getJsonSincronizacion() {
        if (isVacio()) {
            return "[]";
        }
        return items.stream()
                .map(item -> "{\"id\":" + item.producto().getId()
                        + ",\"cantidad\":" + item.cantidad() + "}")
                .collect(Collectors.joining(",", "[", "]"));
    }
}
