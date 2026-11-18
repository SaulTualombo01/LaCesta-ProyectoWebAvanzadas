package ec.minimarket.cesta.payment;

import ec.minimarket.cesta.cart.Pedido;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentOrderRepository extends JpaRepository<Pedido, Long> {

    @Query("select p.id from Pedido p where p.usuarioId = :usuarioId order by p.creadoEn desc, p.id desc")
    Page<Long> buscarIdsDelHistorial(@Param("usuarioId") UUID usuarioId, Pageable pageable);

    @EntityGraph(attributePaths = {"detalles", "detalles.producto", "detalles.producto.categoria"})
    List<Pedido> findByUsuarioIdAndIdInOrderByCreadoEnDescIdDesc(UUID usuarioId, Collection<Long> ids);
}
