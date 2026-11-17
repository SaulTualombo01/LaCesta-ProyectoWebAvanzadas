package ec.minimarket.cesta.payment;

import java.math.BigDecimal;

public record PaymentResult(
        String estado,
        boolean aprobado,
        String codigoAutorizacion,
        String transaccionId,
        String referencia,
        String clientTransactionId,
        Long pedidoId,
        BigDecimal monto,
        String moneda,
        String mensaje,
        String fechaHora,
        boolean modoReal
) {
}
