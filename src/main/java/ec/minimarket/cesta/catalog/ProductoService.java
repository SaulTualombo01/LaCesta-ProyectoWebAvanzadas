package ec.minimarket.cesta.catalog;

import ec.minimarket.cesta.category.Categoria;
import ec.minimarket.cesta.category.CategoriaRepository;

import java.util.Objects;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class ProductoService {

    private final ProductoRepository productos;
    private final ProductoImagenRepository imagenes;
    private final ApplicationEventPublisher eventos;
    private final CategoriaRepository categorias;

    ProductoService(
            ProductoRepository productos,
            ProductoImagenRepository imagenes,
            ApplicationEventPublisher eventos,
            CategoriaRepository categorias) {

        this.productos = productos;
        this.imagenes = imagenes;
        this.eventos = eventos;
        this.categorias = categorias;
    }

    Page<Producto> listar(String categoria, int pagina) {

        var solicitud = PageRequest.of(
                pagina, 8, Sort.by("id").descending());

        return categoria == null || categoria.isBlank()
                ? productos.findAll(solicitud)
                : productos.findByCategoria_Codigo(
                categoria, solicitud);
    }

    Page<Producto> listarOfertas(String categoria, int pagina) {

        var solicitud = PageRequest.of(
                pagina, 8, Sort.by("id").descending());

        return categoria == null || categoria.isBlank()
                ? productos.findByPrecioPromocionalIsNotNullAndStockGreaterThan(
                0, solicitud)
                : productos.findByCategoria_CodigoAndPrecioPromocionalIsNotNullAndStockGreaterThan(
                categoria, 0, solicitud);
    }

    Producto consultar(Long id) {
        return productos.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "El producto no existe."));
    }

    byte[] consultarImagen(Long id) {
        return imagenes.findById(id)
                .map(ProductoImagen::contenido)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "El producto no tiene imagen."));
    }

    private Categoria consultarCategoria(String codigo) {

        Categoria categoria = categorias.findByCodigo(codigo)
                .orElseThrow(() -> new IllegalArgumentException(
                        "La categoría seleccionada no existe."));

        if (!categoria.isActivo()) {
            throw new IllegalArgumentException(
                    "La categoría seleccionada está inactiva.");
        }

        return categoria;
    }

    @Transactional
    Producto crear(ProductoForm form, byte[] imagen) {

        Categoria categoria = consultarCategoria(form.categoria());

        Producto producto = new Producto(form, categoria);

        if (imagen != null) {
            producto.cambiarImagen(true);
        }

        productos.save(producto);

        if (imagen != null) {
            imagenes.save(new ProductoImagen(
                    producto.getId(), imagen));
        }

        return producto;
    }

    @Transactional
    void actualizar(
            Long id,
            ProductoForm form,
            byte[] imagen,
            boolean quitarImagen) {

        Producto producto = consultarVersion(id, form.version());

        int stockAnterior = producto.getStock();

        Categoria categoria = producto.getCategoria();

        String codigoActual = categoria.getCodigo();
        String codigoSeleccionado = form.categoria();

        if (!Objects.equals(codigoActual, codigoSeleccionado)) {
            categoria = consultarCategoria(codigoSeleccionado);
        }

        producto.actualizar(form, categoria);

        if (imagen != null ||
                (quitarImagen && producto.getImagenRevision() != null)) {

            producto.cambiarImagen(imagen != null);

            // Verifica la versión antes de escribir la foto.
            productos.flush();

            if (imagen != null) {
                imagenes.save(new ProductoImagen(id, imagen));
            } else {
                imagenes.deleteById(id);
            }
        }

        if (stockAnterior > 0 && producto.getStock() == 0) {
            eventos.publishEvent(new ProductoAgotado(
                    producto.getId(),
                    producto.getNombre()));
        }
    }

    @Transactional
    void eliminar(Long id, Long version) {
        productos.delete(consultarVersion(id, version));
    }

    private Producto consultarVersion(Long id, Long version) {

        Producto producto = consultar(id);

        if (!Objects.equals(producto.getVersion(), version)) {
            throw new ObjectOptimisticLockingFailureException(
                    Producto.class, id);
        }

        return producto;
    }
}