package ec.minimarket.cesta.catalog;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import java.time.Duration;

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

    private final ProductoService productos;

    ProductoController(ProductoService productos) {
        this.productos = productos;
    }

    @GetMapping("/")
    String inicio(@RequestParam(required = false) Categoria categoria,
                  @RequestParam(defaultValue = "0") int page, HttpServletRequest request, Model model) {
        return request.isUserInRole("ADMIN") ? "redirect:/admin" : listado(categoria, page, true, model);
    }

    @GetMapping("/productos")
    String listado(@RequestParam(required = false) Categoria categoria,
                    @RequestParam(defaultValue = "0") int page, Model model) {
        return listado(categoria, page, false, model);
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
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePrivate())
                .body(productos.consultarImagen(id));
    }

    @GetMapping("/productos/nuevo")
    String nuevo(Model model) {
        model.addAttribute("form", new ProductoForm("", null, "", null, 0, null, null));
        return formulario(model, null);
    }

    @GetMapping("/productos/{id}/editar")
    String editar(@PathVariable Long id, Model model) {
        Producto producto = productos.consultar(id);
        model.addAttribute("form", ProductoForm.desde(producto));
        return formulario(model, producto);
    }

    @PostMapping("/productos")
    String crear(@Valid @ModelAttribute("form") ProductoForm form, BindingResult errores,
                  @RequestParam(required = false) MultipartFile imagen,
                  Model model, RedirectAttributes aviso) {
        validarPromocion(form, errores);
        byte[] foto = errores.hasErrors() ? null : prepararImagen(imagen, model);
        if (errores.hasErrors() || model.containsAttribute("errorImagen")) {
            return formulario(model, null);
        }
        Producto producto = productos.crear(form, foto);
        aviso.addFlashAttribute("exito", "El producto se guardó.");
        return "redirect:/productos/" + producto.getId();
    }

    @PostMapping("/productos/{id}")
    String actualizar(@PathVariable Long id, @Valid @ModelAttribute("form") ProductoForm form,
                       BindingResult errores, @RequestParam(required = false) MultipartFile imagen,
                       @RequestParam(defaultValue = "false") boolean quitarImagen,
                       Model model, RedirectAttributes aviso) {
        validarPromocion(form, errores);
        byte[] foto = errores.hasErrors() ? null : prepararImagen(imagen, model);
        if (errores.hasErrors() || model.containsAttribute("errorImagen")) {
            return formulario(model, productos.consultar(id));
        }
        try {
            productos.actualizar(id, form, foto, quitarImagen);
        } catch (OptimisticLockingFailureException e) {
            aviso.addFlashAttribute("error", "El producto cambió mientras lo editabas. Revisa los datos actuales.");
            return "redirect:/productos/" + id + "/editar";
        }
        aviso.addFlashAttribute("exito", "El producto se actualizó.");
        return "redirect:/productos/" + id;
    }

    @PostMapping("/productos/{id}/eliminar")
    String eliminar(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean confirmar,
                     @RequestParam Long version, RedirectAttributes aviso) {
        if (!confirmar) {
            aviso.addFlashAttribute("error", "Confirma la eliminación del producto.");
            return "redirect:/productos/" + id;
        }
        try {
            productos.eliminar(id, version);
        } catch (OptimisticLockingFailureException e) {
            aviso.addFlashAttribute("error", "El producto cambió. Revisa los datos y vuelve a confirmar.");
            return "redirect:/productos/" + id;
        }
        aviso.addFlashAttribute("exito", "El producto se eliminó.");
        return "redirect:/productos";
    }

    private String listado(Categoria categoria, int page, boolean inicio, Model model) {
        Page<Producto> pagina = productos.listar(categoria, Math.max(0, page));
        if (pagina.isEmpty() && page > 0) {
            pagina = productos.listar(categoria, Math.max(0, pagina.getTotalPages() - 1));
        }
        model.addAttribute("pagina", pagina);
        model.addAttribute("categoria", categoria);
        model.addAttribute("categorias", Categoria.values());
        model.addAttribute("inicio", inicio);
        return "catalog/list";
    }

    private String formulario(Model model, Producto producto) {
        if (producto != null) {
            model.addAttribute("productoId", producto.getId());
            model.addAttribute("imagenActual", producto.getImagenRevision());
        }
        model.addAttribute("categorias", Categoria.values());
        return "catalog/form";
    }

    private byte[] prepararImagen(MultipartFile archivo, Model model) {
        try {
            return FotografiaProducto.preparar(archivo);
        } catch (IllegalArgumentException error) {
            model.addAttribute("errorImagen", error.getMessage());
            return null;
        }
    }

    private void validarPromocion(ProductoForm form, BindingResult errores) {
        if (form.precioPromocional() != null && form.precio() != null
                && form.precioPromocional().compareTo(form.precio()) >= 0) {
            errores.rejectValue("precioPromocional", "producto.promocion", "La promoción debe ser menor al precio regular.");
        }
    }
}
