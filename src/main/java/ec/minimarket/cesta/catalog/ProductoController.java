package ec.minimarket.cesta.catalog;

import ec.minimarket.cesta.category.Categoria;
import ec.minimarket.cesta.category.CategoriaService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
class ProductoController {

    private static final String CODIGO_FILTRO = "[A-Z0-9_]{0,20}";
    private static final String CATEGORIA = "categoria";
    private static final String CATEGORIAS = "categorias";
    private static final String ERROR_IMAGEN = "errorImagen";
    private static final String ERROR = "error";
    private static final String EXITO = "exito";
    private static final String REDIRECT_PRODUCTOS =
            "redirect:/productos/";

    private static final Map<String, String> CATEGORIAS_DESTACADAS;

    static {
        Map<String, String> destacadas = new LinkedHashMap<>();

        destacadas.put("DESPENSA", "ofertas-despensa.jpg");
        destacadas.put("LACTEOS_Y_HUEVOS", "ofertas-lacteos.jpg");
        destacadas.put("BEBIDAS", "ofertas-bebidas.jpg");

        CATEGORIAS_DESTACADAS =
                Collections.unmodifiableMap(destacadas);
    }

    private static final Map<String, String> FOTOS_ALTERNATIVAS =
            Map.of(
                    "DESPENSA",
                    "ofertas-campana-hover-despensa.jpg",

                    "LACTEOS_Y_HUEVOS",
                    "ofertas-campana-hover-lacteos.jpg",

                    "BEBIDAS",
                    "ofertas-campana-hover-bebidas.jpg"
            );

    private final ProductoService productos;
    private final CategoriaService categoriaService;

    ProductoController(
            ProductoService productos,
            CategoriaService categoriaService) {

        this.productos = productos;
        this.categoriaService = categoriaService;
    }

    @GetMapping("/")
    String inicio(
            @RequestParam(required = false) @Pattern(regexp = CODIGO_FILTRO) String categoria,
            @RequestParam(defaultValue = "0") int page,
            HttpServletRequest request,
            Model model) {

        if (request.isUserInRole("ADMIN")) {
            return "redirect:/admin";
        }
        if (request.isUserInRole("DELIVERY")) {
            return "redirect:/entregas";
        }
        return listado(categoria, page, true, model);
    }

    @GetMapping("/productos")
    String listado(
            @RequestParam(required = false) @Pattern(regexp = CODIGO_FILTRO) String categoria,
            @RequestParam(defaultValue = "0") int page,
            Model model) {

        return listado(categoria, page, false, model);
    }

    @GetMapping("/ofertas")
    String ofertas(
            @RequestParam(required = false) @Pattern(regexp = CODIGO_FILTRO) String categoria,
            @RequestParam(defaultValue = "0") int page,
            HttpServletRequest request,
            Model model,
            RedirectAttributes redirect) {

        if (request.isUserInRole("ADMIN")) {
            return "redirect:/admin/ofertas";
        }

        Page<Producto> pagina =
                productos.listarOfertas(categoria, Math.max(0, page));

        if (pagina.isEmpty() && page > 0) {

            if (categoria != null && !categoria.isBlank()) {
                redirect.addAttribute(CATEGORIA, categoria);
            }

            return "redirect:/ofertas";
        }

        model.addAttribute("pagina", pagina);
        model.addAttribute(CATEGORIA, categoria);
        model.addAttribute(
                CATEGORIAS, categoriaService.listarActivas());
        model.addAttribute("destacadas", CATEGORIAS_DESTACADAS);
        model.addAttribute("alternativas", FOTOS_ALTERNATIVAS);

        return "catalog/offers";
    }

    @GetMapping("/productos/{id}")
    String detalle(@PathVariable Long id, Model model) {

        model.addAttribute("producto", productos.consultar(id));

        return "catalog/detail";
    }

    @GetMapping("/productos/{id}/imagen")
    ResponseEntity<byte[]> imagen(@PathVariable Long id) {

        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.noStore())
                .body(productos.consultarImagen(id));
    }

    @GetMapping("/productos/nuevo")
    String nuevo(Model model) {

        model.addAttribute(
                "form",
                new ProductoForm(
                        "", null, "", null, 0, null, null));

        return formulario(model, null);
    }

    @GetMapping("/productos/{id}/editar")
    String editar(@PathVariable Long id, Model model) {

        Producto producto = productos.consultar(id);

        model.addAttribute("form", ProductoForm.desde(producto));

        return formulario(model, producto);
    }

    @PostMapping("/productos")
    String crear(
            @Valid @ModelAttribute("form") ProductoForm form,
            BindingResult errores,
            @RequestParam(required = false) MultipartFile imagen,
            Model model,
            RedirectAttributes aviso) {

        validarPromocion(form, errores);

        byte[] foto = errores.hasErrors()
                ? new byte[0]
                : prepararImagen(imagen, model);

        if (errores.hasErrors()
                || model.containsAttribute(ERROR_IMAGEN)) {

            return formulario(model, null);
        }

        try {
            Producto producto = productos.crear(
                    form, normalizarImagen(foto));

            aviso.addFlashAttribute(
                    EXITO, "El producto se guardó.");

            return REDIRECT_PRODUCTOS + producto.getId();

        } catch (IllegalArgumentException e) {

            errores.rejectValue(
                    CATEGORIA, "producto.categoria", e.getMessage());

            return formulario(model, null);
        }
    }

    @PostMapping("/productos/{id}")
    String actualizar(
            @PathVariable Long id,
            @Valid @ModelAttribute("form") ProductoForm form,
            BindingResult errores,
            @RequestParam(required = false) MultipartFile imagen,
            @RequestParam(defaultValue = "false") boolean quitarImagen,
            Model model,
            RedirectAttributes aviso) {

        validarPromocion(form, errores);

        byte[] foto = errores.hasErrors()
                ? new byte[0]
                : prepararImagen(imagen, model);

        if (errores.hasErrors()
                || model.containsAttribute(ERROR_IMAGEN)) {

            return formulario(model, productos.consultar(id));
        }

        try {
            productos.actualizar(
                    id, form, normalizarImagen(foto), quitarImagen);

        } catch (OptimisticLockingFailureException e) {

            aviso.addFlashAttribute(
                    ERROR,
                    "El producto cambió mientras lo editabas. "
                            + "Revisa los datos actuales.");

            return REDIRECT_PRODUCTOS + id + "/editar";

        } catch (IllegalArgumentException e) {

            errores.rejectValue(
                    CATEGORIA, "producto.categoria", e.getMessage());

            return formulario(model, productos.consultar(id));
        }

        aviso.addFlashAttribute(
                EXITO, "El producto se actualizó.");

        return REDIRECT_PRODUCTOS + id;
    }

    @PostMapping("/productos/{id}/eliminar")
    String eliminar(
            @PathVariable Long id,
            @RequestParam(defaultValue = "false") boolean confirmar,
            @RequestParam @PositiveOrZero Long version,
            RedirectAttributes aviso) {

        if (!confirmar) {

            aviso.addFlashAttribute(
                    ERROR, "Confirma la eliminación del producto.");

            return REDIRECT_PRODUCTOS + id;
        }

        try {
            productos.eliminar(id, version);

        } catch (OptimisticLockingFailureException e) {

            aviso.addFlashAttribute(
                    ERROR,
                    "El producto cambió. Revisa los datos "
                            + "y vuelve a confirmar.");

            return REDIRECT_PRODUCTOS + id;
        }

        aviso.addFlashAttribute(
                EXITO, "El producto se eliminó.");

        return "redirect:/productos";
    }

    private String listado(
            String categoria,
            int page,
            boolean inicio,
            Model model) {

        Page<Producto> pagina =
                productos.listar(categoria, Math.max(0, page));

        if (pagina.isEmpty() && page > 0) {

            pagina = productos.listar(
                    categoria,
                    Math.max(0, pagina.getTotalPages() - 1));
        }

        model.addAttribute("pagina", pagina);
        model.addAttribute(CATEGORIA, categoria);
        model.addAttribute(
                CATEGORIAS, categoriaService.listarActivas());
        model.addAttribute("inicio", inicio);

        return "catalog/list";
    }

    private String formulario(Model model, Producto producto) {

        List<Categoria> disponibles =
                new ArrayList<>(categoriaService.listarActivas());

        if (producto != null) {

            model.addAttribute("productoId", producto.getId());
            model.addAttribute(
                    "imagenActual", producto.getImagenRevision());

            Categoria actual = producto.getCategoria();

            if (!actual.isActivo()) {
                disponibles.add(actual);
            }
        }

        model.addAttribute(CATEGORIAS, disponibles);

        return "catalog/form";
    }

    private byte[] prepararImagen(
            MultipartFile archivo, Model model) {

        try {
            byte[] contenido = FotografiaProducto.preparar(archivo);

            return contenido == null ? new byte[0] : contenido;

        } catch (IllegalArgumentException error) {

            model.addAttribute(ERROR_IMAGEN, error.getMessage());

            return new byte[0];
        }
    }

    @SuppressWarnings("java:S1168")
    private byte[] normalizarImagen(byte[] foto) {

        return foto.length == 0 ? null : foto;
    }

    private void validarPromocion(
            ProductoForm form, BindingResult errores) {

        if (form.precioPromocional() != null
                && form.precio() != null
                && form.precioPromocional()
                .compareTo(form.precio()) >= 0) {

            errores.rejectValue(
                    "precioPromocional",
                    "producto.promocion",
                    "La promoción debe ser menor al precio regular.");
        }
    }
}