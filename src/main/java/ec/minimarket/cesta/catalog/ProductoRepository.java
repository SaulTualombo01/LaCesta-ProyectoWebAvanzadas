package ec.minimarket.cesta.catalog;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductoRepository extends JpaRepository<Producto, Long> {

    Page<Producto> findByCategoria_Codigo(
            String codigo, Pageable pageable);

    Page<Producto> findByPrecioPromocionalIsNotNull(
            Pageable pageable);

    Page<Producto> findByPrecioPromocionalIsNotNullAndStockGreaterThan(
            int stock, Pageable pageable);

    Page<Producto> findByCategoria_CodigoAndPrecioPromocionalIsNotNullAndStockGreaterThan(
            String codigo, int stock, Pageable pageable);

    long countByStock(int stock);

    long countByStockBetween(int minimo, int maximo);

    long countByPrecioPromocionalIsNotNull();

    boolean existsByCategoria_Codigo(String codigo);
}