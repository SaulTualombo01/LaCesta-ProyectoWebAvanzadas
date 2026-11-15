package ec.minimarket.cesta.cart;

import ec.minimarket.cesta.catalog.Producto;
import ec.minimarket.cesta.catalog.ProductoRepository;
import ec.minimarket.cesta.category.Categoria;
import ec.minimarket.cesta.category.CategoriaRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CarritoTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ProductoRepository productos;

    @Autowired
    private CategoriaRepository categorias;

    @Autowired
    private CarritoService carritoService;

    @Test
    void calculaResumenConDescuentosYLimitaAlStockDisponible() throws Exception {
        var cliente = user("cliente").roles("CUSTOMER");
        MockHttpSession session = new MockHttpSession();

        Long idOferta = crearProductoConAdmin("Café Lojano", "DESPENSA", "4.00", "3.00", 5);
        Long idRegular = crearProductoConAdmin("Leche Entera", "LACTEOS_Y_HUEVOS", "1.50", null, 3);

        mvc.perform(post("/carrito/agregar")
                        .session(session)
                        .with(cliente)
                        .with(csrf())
                        .param("productoId", idOferta.toString())
                        .param("cantidad", "2")
                        .param("origen", "/productos"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/productos"));

        mvc.perform(post("/carrito/agregar")
                        .session(session)
                        .with(cliente)
                        .with(csrf())
                        .param("productoId", idRegular.toString())
                        .param("cantidad", "10"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/carrito"));

        ResumenCarrito resumen = carritoService.obtenerResumen(session);
        assertThat(resumen.totalUnidades()).isEqualTo(5);
        assertThat(resumen.subtotalRegular()).isEqualByComparingTo(new BigDecimal("12.50"));
        assertThat(resumen.descuentoPromociones()).isEqualByComparingTo(new BigDecimal("2.00"));
        assertThat(resumen.totalPagar()).isEqualByComparingTo(new BigDecimal("10.50"));

        mvc.perform(get("/carrito").session(session).with(cliente))
                .andExpect(status().isOk())
                .andExpect(view().name("cart/index"))
                .andExpect(content().string(allOf(
                        containsString("Café Lojano"),
                        containsString("Leche Entera"),
                        containsString("-$2.00"),
                        containsString("$10.50"))));
    }

    @Test
    void validaDatosDeEntregaYConfirmaElResumenDelPedido() throws Exception {
        var cliente = user("cliente").roles("CUSTOMER");
        MockHttpSession session = new MockHttpSession();

        Long idProd = crearProductoConAdmin("Pan Artesanal", "DESPENSA", "2.00", null, 4);

        carritoService.agregar(session, idProd, 2);

        mvc.perform(post("/carrito/confirmar")
                        .session(session)
                        .with(cliente)
                        .with(csrf())
                        .param("direccion", "abc")
                        .param("telefono", "invalido"))
                .andExpect(status().isOk())
                .andExpect(view().name("cart/index"))
                .andExpect(model().attributeHasFieldErrors(
                        "entrega", "direccion", "telefono"));

        mvc.perform(post("/carrito/confirmar")
                        .session(session)
                        .with(cliente)
                        .with(csrf())
                        .param("direccion", "Av. 12 de Octubre y Patria")
                        .param("telefono", "0991234567")
                        .param("referencia", "Edificio esquinero"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/carrito/confirmado"));

        mvc.perform(get("/carrito/confirmado").session(session).with(cliente))
                .andExpect(status().isOk())
                .andExpect(view().name("cart/confirmed"))
                .andExpect(content().string(allOf(
                        containsString("cliente"),
                        containsString("Av. 12 de Octubre y Patria"),
                        containsString("0991234567"),
                        containsString("$4.00"))));

        DatosEntregaForm autocompletado = carritoService.obtenerDatosEntrega(session);
        assertThat(autocompletado.direccion()).isEqualTo("Av. 12 de Octubre y Patria");
        assertThat(autocompletado.telefono()).isEqualTo("0991234567");
    }

    private Long crearProductoConAdmin(
            String nombre,
            String categoria,
            String precio,
            String promocion,
            int stock) throws Exception {

        var builder = post("/productos")
                .with(user("admin").roles("ADMIN"))
                .with(csrf())
                .param("nombre", nombre)
                .param("categoria", categoria)
                .param("descripcion", "Producto de prueba")
                .param("precio", precio)
                .param("stock", String.valueOf(stock));

        if (promocion != null) {
            builder.param("precioPromocional", promocion);
        }

        String redirect = mvc.perform(builder)
                .andExpect(status().is3xxRedirection())
                .andReturn()
                .getResponse()
                .getRedirectedUrl();

        return Long.valueOf(redirect.substring(redirect.lastIndexOf('/') + 1));
    }
}
