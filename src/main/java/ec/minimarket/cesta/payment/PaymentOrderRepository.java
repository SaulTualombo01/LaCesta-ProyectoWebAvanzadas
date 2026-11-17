package ec.minimarket.cesta.payment;

import ec.minimarket.cesta.cart.Pedido;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentOrderRepository extends JpaRepository<Pedido, Long> {

    @EntityGraph(attributePaths = {"detalles", "detalles.producto", "detalles.producto.categoria"})
    List<Pedido> findByUsuarioIdOrderByCreadoEnDescIdDesc(UUID usuarioId);
}
