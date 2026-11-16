package ec.minimarket.cesta.cart;

import java.math.BigDecimal;
import java.util.List;

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


}
