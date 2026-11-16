package ec.minimarket.cesta.catalog;

import java.util.Collection;
import java.util.List;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductoRepository extends JpaRepository<Producto, Long> {

    Page<Producto> findByCategoria_Codigo(
            String codigo, Pageable pageable);

    Page<Producto> findByPrecioPromocionalIsNotNull(
            Pageable pageable);

    Page<Producto> findByPrecioPromocionalIsNotNullAndStockGreaterThan(
            int stock, Pageable pageable);

    Page<Producto> findByCategoria_CodigoAndPrecioPromocionalIsNotNullAndStockGreaterThan(
            String codigo, int stock, Pageable pageable);

    @EntityGraph(attributePaths = "categoria")
    List<Producto> findByIdIn(Collection<Long> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Producto p where p.id in :ids order by p.id")
    List<Producto> bloquearStock(@Param("ids") Collection<Long> ids);

    long countByStock(int stock);

    long countByStockBetween(int minimo, int maximo);

    long countByPrecioPromocionalIsNotNull();

    boolean existsByCategoria_Codigo(String codigo);
}