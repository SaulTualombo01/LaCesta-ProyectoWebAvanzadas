package ec.minimarket.cesta.payment;

import ec.minimarket.cesta.catalog.Producto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentProductRepository extends JpaRepository<Producto, Long> {

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Producto p SET p.stock = p.stock - :cantidad, p.version = p.version + 1 "
            + "WHERE p.id = :id AND p.stock >= :cantidad")
    int descontarStock(@Param("id") Long id, @Param("cantidad") int cantidad);
}
