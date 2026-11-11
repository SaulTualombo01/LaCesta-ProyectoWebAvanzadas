package ec.minimarket.cesta.catalog;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface ProductoRepository extends JpaRepository<Producto, Long> {

    Page<Producto> findByCategoria(Categoria categoria, Pageable pageable);

    long countByStock(int stock);

    long countByStockBetween(int minimo, int maximo);

    long countByPrecioPromocionalIsNotNull();
}
