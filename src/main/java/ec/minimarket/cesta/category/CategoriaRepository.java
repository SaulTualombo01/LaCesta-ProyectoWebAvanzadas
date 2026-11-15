package ec.minimarket.cesta.category;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CategoriaRepository
        extends JpaRepository<Categoria, Long> {

    List<Categoria> findAllByOrderByNombreAsc();

    List<Categoria> findByActivoTrueOrderByNombreAsc();

    Optional<Categoria> findByCodigo(String codigo);

    boolean existsByCodigoIgnoreCase(String codigo);

    boolean existsByNombreIgnoreCase(String nombre);
}