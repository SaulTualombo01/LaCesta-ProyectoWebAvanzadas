package ec.minimarket.cesta.catalog;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
class AdministracionController {

    private static final int STOCK_BAJO = 5;
    private final ProductoRepository productos;

    AdministracionController(ProductoRepository productos) {
        this.productos = productos;
    }

    @GetMapping("/admin")
    String panel(@RequestParam(defaultValue = "0") int page, Model model) {
        var solicitud = PageRequest.of(Math.max(0, page), 10, Sort.by("stock", "id"));
        var inventario = productos.findAll(solicitud);
        if (inventario.isEmpty() && page > 0) {
            return "redirect:/admin";
        }
        model.addAttribute("inventario", inventario);
        model.addAttribute("agotados", productos.countByStock(0));
        model.addAttribute("stockBajo", productos.countByStockBetween(1, STOCK_BAJO));
        model.addAttribute("limiteStockBajo", STOCK_BAJO);
        model.addAttribute("promociones", productos.countByPrecioPromocionalIsNotNull());
        return "admin/index";
    }

    @GetMapping("/admin/ofertas")
    String ofertas(@RequestParam(defaultValue = "0") int page, Model model) {
        var solicitud = PageRequest.of(Math.max(0, page), 10, Sort.by("id").descending());
        var ofertas = productos.findByPrecioPromocionalIsNotNull(solicitud);
        if (ofertas.isEmpty() && page > 0) {
            return "redirect:/admin/ofertas";
        }
        model.addAttribute("ofertas", ofertas);
        return "admin/offers";
    }
}

