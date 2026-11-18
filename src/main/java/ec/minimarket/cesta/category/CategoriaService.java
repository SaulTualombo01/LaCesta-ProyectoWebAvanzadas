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

    public Categoria crear(String codigoIngresado, String nombreIngresado, String descripcion) {

        String codigo = codigoIngresado == null ? "" : codigoIngresado.strip().toUpperCase(Locale.ROOT);
        String nombre = validarNombre(nombreIngresado);
        validarDescripcion(descripcion);

        if (!codigo.matches("[A-Z0-9_]{1,20}")) {
            throw new IllegalArgumentException(
                    "El código de la categoría no es válido");
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
        nueva.setDescripcion(descripcion);
        nueva.setActivo(true);

        return repository.save(nueva);
    }

    public Categoria actualizar(Long id, String nombreIngresado, String descripcion) {

        Categoria categoria = buscarPorId(id);
        String nombre = validarNombre(nombreIngresado);
        validarDescripcion(descripcion);

        if (!categoria.getNombre().equalsIgnoreCase(nombre)
                && repository.existsByNombreIgnoreCase(nombre)) {
            throw new IllegalArgumentException(
                    "Ya existe una categoría con ese nombre");
        }

        categoria.setNombre(nombre);
        categoria.setDescripcion(descripcion);

        return repository.save(categoria);
    }

    private static String validarNombre(String nombreIngresado) {
        String nombre = nombreIngresado == null ? "" : nombreIngresado.strip();
        if (nombre.isBlank() || nombre.length() > 100 || nombre.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Escribe un nombre de categoría válido de hasta 100 caracteres.");
        }
        return nombre;
    }

    private static void validarDescripcion(String descripcion) {
        if (descripcion != null && (descripcion.length() > 255 || descripcion.indexOf(0) >= 0)) {
            throw new IllegalArgumentException("Escribe una descripción válida de hasta 255 caracteres.");
        }
    }

    public void cambiarEstado(Long id) {
        Categoria categoria = buscarPorId(id);
        categoria.setActivo(!categoria.isActivo());
        repository.save(categoria);
    }
}