package ec.minimarket.cesta.cart;

import ec.minimarket.cesta.account.CestaPrincipal;
import ec.minimarket.cesta.catalog.ProductoRepository;
import ec.minimarket.cesta.category.CategoriaRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CarritoTest {

    private static final UUID CLIENTE = UUID.fromString("00000000-0000-0000-0000-000000000021");
    private static final UUID OTRO = UUID.fromString("00000000-0000-0000-0000-000000000022");

    private static org.springframework.test.web.servlet.request.RequestPostProcessor cliente(UUID id) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(
                new CestaPrincipal(id, "cliente"), null, AuthorityUtils.createAuthorityList("ROLE_CUSTOMER")));
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ProductoRepository productos;

    @Autowired
    private CategoriaRepository categorias;

    @Autowired
    private PedidoRepository pedidos;

    @Autowired
    private CarritoService carritoService;

    @Test
    void calculaResumenConDescuentosYLimitaAlStockDisponible() throws Exception {
        var cliente = cliente(CLIENTE);
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

        ResumenCarrito resumen = carritoService.obtenerResumen(CLIENTE);
        assertThat(resumen.totalUnidades()).isEqualTo(5);
        assertThat(resumen.subtotalRegular()).isEqualByComparingTo(new BigDecimal("12.50"));
        assertThat(resumen.descuentoPromociones()).isEqualByComparingTo(new BigDecimal("2.00"));
        assertThat(resumen.totalPagar()).isEqualByComparingTo(new BigDecimal("10.50"));

        mvc.perform(get("/carrito").session(new MockHttpSession()).with(cliente))
                .andExpect(status().isOk())
                .andExpect(view().name("cart/index"))
                .andExpect(content().string(allOf(
                        containsString("Café Lojano"),
                        containsString("Leche Entera"),
                        containsString("-$2.00"),
                        containsString("$10.50"))));

        mvc.perform(get("/carrito").with(cliente(OTRO)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Tu carrito está vacío")));

        mvc.perform(post("/carrito/items/{id}/cantidad", idOferta).with(cliente)
                        .param("ajax", "true").param("cantidad", "4"))
                .andExpect(status().isForbidden());

        mvc.perform(post("/carrito/items/{id}/cantidad", idOferta).with(cliente).with(csrf())
                        .param("ajax", "true").param("cantidad", "99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(idOferta.toString()))
                .andExpect(jsonPath("$.items[0].cantidad").value(5))
                .andExpect(jsonPath("$.items[0].precioFinal").value(3));

        mvc.perform(get("/carrito").with(cliente))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("$19.50")));
    }

    @Test
    void validaDatosDeEntregaYGuardaElPedidoEnBaseDeDatos() throws Exception {
        var cliente = cliente(CLIENTE);
        MockHttpSession session = new MockHttpSession();

        Long idProd = crearProductoConAdmin("Pan Artesanal", "DESPENSA", "2.00", null, 4);

        carritoService.agregar(CLIENTE, idProd, 2);
        String intento = carritoService.obtenerVista(CLIENTE).intento().toString();

        mvc.perform(post("/carrito/confirmar")
                        .session(session)
                        .with(cliente)
                        .with(csrf())
                        .param("intentoCompra", intento)
                        .param("direccion", "  abc  ")
                        .param("telefono", "+(  )--")
                        .param("referencia", "x".repeat(251)))
                .andExpect(status().isOk())
                .andExpect(view().name("cart/index"))
                .andExpect(model().attributeHasFieldErrors(
                        "entrega", "direccion", "telefono", "referencia"));

        String redirectUrl = mvc.perform(post("/carrito/confirmar")
                        .session(session)
                        .with(cliente)
                        .with(csrf())
                        .param("intentoCompra", intento)
                        .param("direccion", "Av. 12 de Octubre y Patria")
                        .param("telefono", "0991234567")
                        .param("referencia", "Edificio esquinero"))
                .andExpect(status().is3xxRedirection())
                .andReturn()
                .getResponse()
                .getRedirectedUrl();

        assertThat(redirectUrl).startsWith("/carrito/confirmado/");
        mvc.perform(post("/carrito/confirmar").with(cliente).with(csrf())
                        .param("intentoCompra", intento)
                        .param("direccion", "Av. 12 de Octubre y Patria").param("telefono", "0991234567"))
                .andExpect(redirectedUrl(redirectUrl));
        assertThat(productos.findById(idProd).orElseThrow().getStock()).isEqualTo(2);

        List<Pedido> guardados = pedidos.findAll();
        assertThat(guardados).hasSize(1);
        mvc.perform(get(redirectUrl).with(cliente(OTRO)))
                .andExpect(redirectedUrl("/carrito"));
        mvc.perform(post("/carrito/pedidos/{id}/restaurar", guardados.get(0).getId())
                        .with(cliente(OTRO)).with(csrf()))
                .andExpect(redirectedUrl("/carrito"));
        assertThat(pedidos.count()).isEqualTo(1);
        assertThat(carritoService.obtenerResumen(CLIENTE).isVacio()).isTrue();
        Pedido guardado = guardados.get(0);
        assertThat(guardado.getDireccionEntrega()).isEqualTo("Av. 12 de Octubre y Patria");
        assertThat(guardado.getTelefonoContacto()).isEqualTo("0991234567");
        assertThat(guardado.getTotal()).isEqualByComparingTo(new BigDecimal("4.00"));
        assertThat(guardado.getDetalles()).hasSize(1);
        assertThat(guardado.getDetalles().get(0).getCantidad()).isEqualTo(2);

        mvc.perform(get(redirectUrl).session(session).with(cliente))
                .andExpect(status().isOk())
                .andExpect(view().name("cart/confirmed"))
                .andExpect(content().string(allOf(
                        containsString("cliente"),
                        containsString("Av. 12 de Octubre y Patria"),
                        containsString("0991234567"),
                        containsString("$4.00"))));

        DatosEntregaForm autocompletado = carritoService.obtenerDatosEntrega(session, CLIENTE);
        assertThat(autocompletado.direccion()).isEqualTo("Av. 12 de Octubre y Patria");
        assertThat(autocompletado.telefono()).isEqualTo("0991234567");

        mvc.perform(get("/admin/pedidos").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/pedidos"))
                .andExpect(content().string(allOf(
                        containsString("Pedidos confirmados"),
                        containsString("Pan Artesanal"),
                        containsString("Av. 12 de Octubre y Patria"),
                        containsString("En espera"),
                        containsString("$4.00"))));

        mvc.perform(get("/entregas").with(user("delivery@admin.com").roles("DELIVERY")))
                .andExpect(status().isOk())
                .andExpect(view().name("cart/entregas"))
                .andExpect(content().string(allOf(
                        containsString("Pedidos por entregar"),
                        containsString("Pan Artesanal"),
                        containsString("En espera"),
                        containsString("Marcar entregado"))));

        mvc.perform(post("/entregas/" + guardado.getId() + "/estado")
                        .with(user("delivery@admin.com").roles("DELIVERY"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/entregas"));

        Pedido actualizado = pedidos.findById(guardado.getId()).orElseThrow();
        assertThat(actualizado.getEstado()).isEqualTo(Pedido.ESTADO_ENTREGADO);
        assertThat(actualizado.isEntregado()).isTrue();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void unaSolaCuentaPuedeConfirmarLaUltimaUnidad() throws Exception {
        UUID primera = UUID.randomUUID();
        UUID segunda = UUID.randomUUID();
        Long productoId = crearProductoConAdmin("Última unidad de prueba", "DESPENSA", "3.00", null, 1);
        var salida = new CountDownLatch(1);
        var concurrencia = Executors.newFixedThreadPool(2);
        try {
            carritoService.agregar(primera, productoId, 1);
            carritoService.agregar(segunda, productoId, 1);
            var intentoPrimera = carritoService.obtenerVista(primera).intento();
            var intentoSegunda = carritoService.obtenerVista(segunda).intento();
            var entrega = new DatosEntregaForm("Av. de prueba 123", "0991234567", "");
            var a = concurrencia.submit(() -> {
                salida.await();
                return carritoService.confirmarPedido(primera, intentoPrimera, entrega);
            });
            var b = concurrencia.submit(() -> {
                salida.await();
                return carritoService.confirmarPedido(segunda, intentoSegunda, entrega);
            });
            salida.countDown();
            var pedidoA = a.get(15, TimeUnit.SECONDS);
            var pedidoB = b.get(15, TimeUnit.SECONDS);
            assertThat(pedidoA == null).isNotEqualTo(pedidoB == null);
            var confirmado = pedidoA == null ? pedidoB : pedidoA;
            UUID ganador = confirmado.getUsuarioId();
            assertThat(productos.findById(productoId).orElseThrow().getStock()).isZero();
            assertThat(carritoService.confirmarPedido(ganador, confirmado.getIntentoCompra(), entrega).getId())
                    .isEqualTo(confirmado.getId());
            assertThat(carritoService.restaurarPedidoAlCarrito(ganador, confirmado.getId())).isTrue();
            assertThat(productos.findById(productoId).orElseThrow().getStock()).isEqualTo(1);
        } finally {
            concurrencia.shutdownNow();
            for (UUID cuenta : List.of(primera, segunda)) {
                pedidos.findFirstByUsuarioIdOrderByCreadoEnDesc(cuenta).ifPresent(pedidos::delete);
                carritoService.vaciar(cuenta);
            }
            productos.deleteById(productoId);
        }
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
