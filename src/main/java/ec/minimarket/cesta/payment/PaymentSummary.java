package ec.minimarket.cesta.payment;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record PaymentSummary(
        Long pedidoId,
        String referencia,
        String comercio,
        String cliente,
        String telefono,
        String direccion,
        BigDecimal subtotal,
        BigDecimal descuento,
        BigDecimal total,
        long montoCentavos,
        String moneda,
        int totalUnidades,
        String fechaPedido,
        boolean pedidoReal,
        List<PaymentLine> items
) {
    public boolean tieneDescuento() {
        return descuento != null && descuento.compareTo(BigDecimal.ZERO) > 0;
    }

    public record PaymentLine(
            String nombre,
            int cantidad,
            BigDecimal precioUnitario,
            BigDecimal subtotal,
            Long productoId,
            UUID imagenRevision,
            String categoriaNombre
    ) {
        public PaymentLine(String nombre, int cantidad, BigDecimal precioUnitario, BigDecimal subtotal) {
            this(nombre, cantidad, precioUnitario, subtotal, null, null, null);
        }
    }
}
