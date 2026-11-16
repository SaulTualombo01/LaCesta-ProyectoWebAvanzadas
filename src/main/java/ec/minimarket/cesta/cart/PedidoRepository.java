package ec.minimarket.cesta.cart;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PedidoRepository extends JpaRepository<Pedido, Long> {

    @EntityGraph(attributePaths = {"detalles", "detalles.producto", "detalles.producto.categoria"})
    Optional<Pedido> findWithDetallesByIdAndUsuarioId(Long id, UUID usuarioId);

    @EntityGraph(attributePaths = {"detalles", "detalles.producto", "detalles.producto.categoria"})
    List<Pedido> findAllByOrderByCreadoEnDescIdDesc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Pedido p where p.id = :id")
    Optional<Pedido> bloquear(@Param("id") Long id);

    Optional<Pedido> findByUsuarioIdAndIntentoCompra(UUID usuarioId, UUID intentoCompra);

    Optional<Pedido> findFirstByUsuarioIdOrderByCreadoEnDesc(UUID usuarioId);
}
