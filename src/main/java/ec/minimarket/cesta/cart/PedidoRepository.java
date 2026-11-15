package ec.minimarket.cesta.cart;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PedidoRepository extends JpaRepository<Pedido, Long> {

    @EntityGraph(attributePaths = {"detalles", "detalles.producto", "detalles.producto.categoria"})
    Optional<Pedido> findWithDetallesById(Long id);

    Optional<Pedido> findFirstByUsuarioIdOrderByCreadoEnDesc(UUID usuarioId);
}
