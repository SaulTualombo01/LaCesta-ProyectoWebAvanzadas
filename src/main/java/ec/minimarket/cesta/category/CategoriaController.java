package ec.minimarket.cesta.category;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/categorias")
public class CategoriaController {

    private static final String REDIRECT =
            "redirect:/admin/categorias";

    private static final String EXITO = "exito";
    private static final String ERROR = "error";

    private final CategoriaService service;

    public CategoriaController(CategoriaService service) {
        this.service = service;
    }

    @GetMapping
    public String listar(Model model) {

        var categorias = service.listarTodas();

        long totalActivas = categorias.stream()
                .filter(Categoria::isActivo)
                .count();

        long totalInactivas = categorias.size() - totalActivas;

        model.addAttribute("categorias", categorias);
        model.addAttribute("totalActivas", totalActivas);
        model.addAttribute("totalInactivas", totalInactivas);

        return "admin/categorias/index";
    }

    @GetMapping("/nueva")
    public String nueva(Model model) {

        model.addAttribute("categoria", new Categoria());

        return "admin/categorias/form";
    }

    @PostMapping("/crear")
    public String crear(
            @RequestParam(defaultValue = "") String codigo,
            @RequestParam(defaultValue = "") String nombre,
            @RequestParam(required = false) String descripcion,
            RedirectAttributes redirect) {

        try {
            service.crear(codigo, nombre, descripcion);

            redirect.addFlashAttribute(
                    EXITO, "Categoría creada correctamente");

        } catch (IllegalArgumentException e) {

            redirect.addFlashAttribute(ERROR, e.getMessage());

            return REDIRECT + "/nueva";
        }

        return REDIRECT;
    }

    @GetMapping("/{id}/editar")
    public String editar(
            @PathVariable Long id,
            Model model,
            RedirectAttributes redirect) {

        try {
            model.addAttribute(
                    "categoria", service.buscarPorId(id));

            return "admin/categorias/form";

        } catch (IllegalArgumentException e) {

            redirect.addFlashAttribute(ERROR, e.getMessage());

            return REDIRECT;
        }
    }

    @PostMapping("/{id}/actualizar")
    public String actualizar(
            @PathVariable Long id,
            @RequestParam(defaultValue = "") String nombre,
            @RequestParam(required = false) String descripcion,
            RedirectAttributes redirect) {

        try {
            service.actualizar(id, nombre, descripcion);

            redirect.addFlashAttribute(
                    EXITO, "Categoría actualizada correctamente");

        } catch (IllegalArgumentException e) {

            redirect.addFlashAttribute(ERROR, e.getMessage());

            return REDIRECT + "/" + id + "/editar";
        }

        return REDIRECT;
    }

    @PostMapping("/{id}/estado")
    public String cambiarEstado(
            @PathVariable Long id,
            RedirectAttributes redirect) {

        try {
            service.cambiarEstado(id);

            redirect.addFlashAttribute(
                    EXITO, "Estado actualizado correctamente");

        } catch (IllegalArgumentException e) {

            redirect.addFlashAttribute(ERROR, e.getMessage());
        }

        return REDIRECT;
    }
}