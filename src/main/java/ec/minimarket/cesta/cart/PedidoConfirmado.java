package ec.minimarket.cesta.cart;

import java.time.Instant;
import java.util.UUID;

public record PedidoConfirmado(
        String codigo,
        Instant fecha,
        UUID usuarioId,
        String cliente,
        DatosEntregaForm entrega,
        ResumenCarrito resumen
) {
}
