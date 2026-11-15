package ec.minimarket.cesta.category;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Service
@Transactional
public class CategoriaService {

    private final CategoriaRepository repository;

    public CategoriaService(CategoriaRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<Categoria> listarTodas() {
        return repository.findAllByOrderByNombreAsc();
    }

    @Transactional(readOnly = true)
    public List<Categoria> listarActivas() {
        return repository.findByActivoTrueOrderByNombreAsc();
    }

    @Transactional(readOnly = true)
    public Categoria buscarPorId(Long id) {
        return repository.findById(id)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Categoría no encontrada"));
    }

    public Categoria crear(Categoria categoria) {

        String codigo = categoria.getCodigo().trim()
                .toUpperCase(Locale.ROOT);
        String nombre = categoria.getNombre().trim();

        if (!codigo.matches("[A-Z0-9_]{1,20}")) {
            throw new IllegalArgumentException(
                    "El código de la categoría no es válido");
        }

        if (nombre.isBlank() || nombre.length() > 100) {
            throw new IllegalArgumentException(
                    "El nombre de la categoría no es válido");
        }

        if (repository.existsByCodigoIgnoreCase(codigo)) {
            throw new IllegalArgumentException(
                    "Ya existe una categoría con ese código");
        }

        if (repository.existsByNombreIgnoreCase(nombre)) {
            throw new IllegalArgumentException(
                    "Ya existe una categoría con ese nombre");
        }

        Categoria nueva = new Categoria();
        nueva.setCodigo(codigo);
        nueva.setNombre(nombre);
        nueva.setDescripcion(categoria.getDescripcion());
        nueva.setActivo(true);

        return repository.save(nueva);
    }

    public Categoria actualizar(Long id, Categoria datos) {

        Categoria categoria = buscarPorId(id);
        String nombre = datos.getNombre().trim();

        if (nombre.isBlank() || nombre.length() > 100) {
            throw new IllegalArgumentException(
                    "El nombre de la categoría no es válido");
        }

        if (!categoria.getNombre().equalsIgnoreCase(nombre)
                && repository.existsByNombreIgnoreCase(nombre)) {
            throw new IllegalArgumentException(
                    "Ya existe una categoría con ese nombre");
        }

        categoria.setNombre(nombre);
        categoria.setDescripcion(datos.getDescripcion());

        return repository.save(categoria);
    }

    public void cambiarEstado(Long id) {
        Categoria categoria = buscarPorId(id);
        categoria.setActivo(!categoria.isActivo());
        repository.save(categoria);
    }
}