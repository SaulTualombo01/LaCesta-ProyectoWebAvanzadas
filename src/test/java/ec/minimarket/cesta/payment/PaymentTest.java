package ec.minimarket.cesta.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import ec.minimarket.cesta.account.CestaPrincipal;
import ec.minimarket.cesta.cart.CarritoService;
import ec.minimarket.cesta.cart.DatosEntregaForm;
import ec.minimarket.cesta.cart.Pedido;
import ec.minimarket.cesta.cart.PedidoRepository;
import ec.minimarket.cesta.catalog.Producto;
import ec.minimarket.cesta.catalog.ProductoRepository;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PaymentTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private CarritoService carritoService;

    @Autowired
    private ProductoRepository productoRepository;

    @Autowired
    private PedidoRepository pedidoRepository;

    @Autowired
    private PaymentService paymentService;

    @Test
    void protegeLasRutasDePasarelaContraAccesosAnonimos() throws Exception {
        mvc.perform(get("/pagos"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(get("/pagos/pedido/1"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(get("/pagos/payphone"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(get("/pagos/resultado"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void muestraElCheckoutYRedirigeAlBotonPayPhoneYSimulaResultado() throws Exception {
        UUID duenioId = UUID.randomUUID();
        var duenio = clienteAuth(duenioId, "María Cliente");
        Pedido pedido = crearPedidoDePrueba(duenioId, "Café Orgánico", "3.50", 2);

        String referenciaEsperada = "LC-PED-" + String.format("%05d", pedido.getId());

        mvc.perform(get("/pagos/pedido/" + pedido.getId()).with(authentication(duenio)))
                .andExpect(status().isOk())
                .andExpect(view().name("payment/checkout"))
                .andExpect(content().string(allOf(
                        containsString("Pasarela de pago"),
                        containsString(referenciaEsperada),
                        containsString("$7.00"),
                        containsString("Pagar con PayPhone"))));

        String redirectPayPhone = mvc.perform(post("/pagos/payphone/iniciar")
                        .with(authentication(duenio))
                        .with(csrf())
                        .param("pedidoId", pedido.getId().toString()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/pagos/payphone?pedidoId=" + pedido.getId() + "&tx=*"))
                .andReturn()
                .getResponse()
                .getRedirectedUrl();

        String tx = redirectPayPhone.substring(redirectPayPhone.indexOf("tx=") + 3);

        mvc.perform(get(redirectPayPhone).with(authentication(duenio)))
                .andExpect(status().isOk())
                .andExpect(view().name("payment/payphone"))
                .andExpect(content().string(allOf(
                        containsString("Pago con PayPhone"),
                        containsString("https://play.google.com/store/apps/details?id=payPhone_Android.PayPhone_Android&hl=es"),
                        containsString(referenciaEsperada),
                        containsString("$7.00 USD"),
                        containsString("700 centavos"),
                        containsString("Autorizar pago (demo)"))));

        String redirectResultado = mvc.perform(post("/pagos/payphone/simular")
                        .with(authentication(duenio))
                        .with(csrf())
                        .param("pedidoId", pedido.getId().toString())
                        .param("tx", tx)
                        .param("estado", "aprobado"))
                .andExpect(status().is3xxRedirection())
                .andReturn()
                .getResponse()
                .getRedirectedUrl();

        assertThat(redirectResultado).startsWith("/pagos/resultado?tx=");

        mvc.perform(get(redirectResultado).with(authentication(duenio)))
                .andExpect(status().isOk())
                .andExpect(view().name("payment/result"))
                .andExpect(content().string(allOf(
                        containsString("Pago autorizado"),
                        containsString("APROBADO"),
                        containsString(referenciaEsperada),
                        containsString("$7.00 USD"))));
    }

    @Test
    void simulaCancelacionYPermiteReintentarDesdeElComprobante() throws Exception {
        UUID duenioId = UUID.randomUUID();
        var duenio = clienteAuth(duenioId, "Elena Cliente");
        Pedido pedido = crearPedidoDePrueba(duenioId, "Leche Descremada", "1.40", 2);

        String redirect = mvc.perform(post("/pagos/payphone/simular")
                        .with(authentication(duenio))
                        .with(csrf())
                        .param("pedidoId", pedido.getId().toString())
                        .param("tx", "LC-PED-" + String.format("%05d", pedido.getId()) + "-CANCEL")
                        .param("estado", "cancelado"))
                .andExpect(status().is3xxRedirection())
                .andReturn()
                .getResponse()
                .getRedirectedUrl();

        mvc.perform(get(redirect).with(authentication(duenio)))
                .andExpect(status().isOk())
                .andExpect(view().name("payment/result"))
                .andExpect(content().string(allOf(
                        containsString("Pago no completado"),
                        containsString("CANCELADO"),
                        containsString("Reintentar con PayPhone"))));
    }

    @Test
    void preparaYConfirmaTransaccionContraPayPhoneApiSimulada() throws Exception {
        PayPhoneGatewayProperties props = new PayPhoneGatewayProperties(
                "La Cesta Minimarket",
                "USD",
                "LC-PED-",
                "https://pay.payphonetodoesposible.com",
                "token-prueba-123",
                "store-id-456",
                "https://localhost:8080"
        );
        var json = new ObjectMapper();
        var paths = new ArrayList<String>();
        RestClient.Builder builder = RestClient.builder().requestInterceptor((request, body, execution) -> {
            assertThat(request.getMethod()).isEqualTo(HttpMethod.POST);
            assertThat(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer token-prueba-123");
            String path = request.getURI().getPath();
            paths.add(path);
            var payload = json.readTree(body);
            if ("/api/button/Prepare".equals(path)) {
                assertThat(payload.get("amount").asLong()).isEqualTo(700L);
                assertThat(payload.get("storeId").asText()).isEqualTo("store-id-456");
                assertThat(payload.get("currency").asText()).isEqualTo("USD");
                return jsonResponse("""
                        {"paymentId":"98765","payWithPayPhone":"https://pay.payphonetodoesposible.com/Pay/98765"}
                        """);
            }
            assertThat(path).isEqualTo("/api/button/V2/Confirm");
            assertThat(payload.get("id").asLong()).isEqualTo(554433L);
            assertThat(payload.get("clientTxId").asText()).isEqualTo("LC-PED-00001-1");
            return jsonResponse("""
                    {"statusCode":3,"transactionStatus":"Approved","transactionId":554433,
                     "authorizationCode":"AUTH-991","clientTransactionId":"LC-PED-00001-1",
                     "amount":700,"message":"Approved"}
                    """);
        });

        PayPhoneClient client = new PayPhoneClient(builder, props);
        PaymentSummary summary = new PaymentSummary(
                1L,
                "LC-PED-00001",
                "La Cesta Minimarket",
                "María Cliente",
                "0991234567",
                "Av. Amazonas",
                new java.math.BigDecimal("7.00"),
                java.math.BigDecimal.ZERO,
                new java.math.BigDecimal("7.00"),
                700L,
                "USD",
                2,
                "15/11/2026 20:00",
                true,
                java.util.List.of()
        );

        PayPhoneClient.PrepareResponse prepared = client.prepare(summary, "LC-PED-00001-1");
        assertThat(prepared.payWithPayPhone()).isEqualTo("https://pay.payphonetodoesposible.com/Pay/98765");

        PayPhoneClient.ConfirmResponse confirmed = client.confirm(554433L, "LC-PED-00001-1");
        assertThat(confirmed.isApproved()).isTrue();
        assertThat(confirmed.authorizationCode()).isEqualTo("AUTH-991");
        assertThat(paths).containsExactly("/api/button/Prepare", "/api/button/V2/Confirm");
    }

    private static MockClientHttpResponse jsonResponse(String json) {
        var response = new MockClientHttpResponse(json.getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return response;
    }

    @Test
    void impideQueOtroClienteAccedaAlPagoDeUnPedidoAjeno() throws Exception {
        UUID duenioId = UUID.randomUUID();
        UUID otroClienteId = UUID.randomUUID();
        Pedido pedido = crearPedidoDePrueba(duenioId, "Jugo Natural", "2.25", 1);

        mvc.perform(get("/pagos/pedido/" + pedido.getId())
                        .with(authentication(clienteAuth(otroClienteId, "Otro Usuario"))))
                .andExpect(status().isForbidden());

        mvc.perform(post("/pagos/payphone/iniciar")
                        .with(authentication(clienteAuth(otroClienteId, "Otro Usuario")))
                        .with(csrf())
                        .param("pedidoId", pedido.getId().toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    void muestraVistaGeneralDePasarelaCuandoNoHayPedidoPrevio() throws Exception {
        var cliente = clienteAuth(UUID.randomUUID(), "Carlos Prueba");

        mvc.perform(get("/pagos").with(authentication(cliente)))
                .andExpect(status().isOk())
                .andExpect(view().name("payment/checkout"))
                .andExpect(content().string(allOf(
                        containsString("LC-DEMO-00001"),
                        containsString("Pagar con PayPhone"),
                        containsString("$12.50"))));
    }

    @Test
    void leeCarritoActivoAntesDeConfirmarYNoMuestraBotonPagarEnResumenConfirmado() throws Exception {
        UUID clienteId = UUID.randomUUID();
        var cliente = clienteAuth(clienteId, "Laura Compra");

        String redirectProd = mvc.perform(post("/productos")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .param("nombre", "Pan de Yuca")
                        .param("categoria", "DESPENSA")
                        .param("descripcion", "Pan fresco")
                        .param("precio", "2.70")
                        .param("stock", "8"))
                .andExpect(status().is3xxRedirection())
                .andReturn()
                .getResponse()
                .getRedirectedUrl();
        Long productoId = Long.valueOf(redirectProd.substring(redirectProd.lastIndexOf('/') + 1));

        carritoService.agregar(clienteId, productoId, 1);
        MockHttpSession session = new MockHttpSession();

        mvc.perform(get("/carrito").session(session).with(authentication(cliente)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Confirmar pedido e ir al pago")));

        mvc.perform(get("/pagos").session(session).with(authentication(cliente)))
                .andExpect(status().isOk())
                .andExpect(view().name("payment/checkout"))
                .andExpect(content().string(allOf(
                        containsString("LC-CARRITO"),
                        containsString("Pan de Yuca"),
                        containsString("$2.70"))));

        UUID intento = paymentService.obtenerIntentoCompra(null, clienteId);
        Pedido pedido = carritoService.confirmarPedido(
                clienteId,
                intento,
                new DatosEntregaForm("Av. Shyris", "0991234567", "")
        );

        mvc.perform(get("/carrito/confirmado/" + pedido.getId()).session(session).with(authentication(cliente)))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Pagar con PayPhone"))));
    }

    @Test
    void alSimularPagoAprobadoDescuentaElStockEnLaBaseDeDatosYAlCancelarNo() throws Exception {
        UUID clienteId = UUID.randomUUID();
        var cliente = clienteAuth(clienteId, "Andrés Pago");

        String redirectProd = mvc.perform(post("/productos")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .param("nombre", "Yogurt Griego")
                        .param("categoria", "LACTEOS_Y_HUEVOS")
                        .param("descripcion", "Yogurt natural")
                        .param("precio", "3.00")
                        .param("stock", "10"))
                .andExpect(status().is3xxRedirection())
                .andReturn()
                .getResponse()
                .getRedirectedUrl();
        Long productoId = Long.valueOf(redirectProd.substring(redirectProd.lastIndexOf('/') + 1));

        MockHttpSession session = new MockHttpSession();
        carritoService.agregar(clienteId, productoId, 3);

        // Cancelar no debe descontar stock
        mvc.perform(post("/pagos/payphone/simular")
                        .session(session)
                        .with(authentication(cliente))
                        .with(csrf())
                        .param("tx", "LC-CARRITO-CANCEL-1")
                        .param("estado", "cancelado"))
                .andExpect(status().is3xxRedirection());

        Producto trasCancelar = productoRepository.findById(productoId).orElseThrow();
        assertThat(trasCancelar.getStock()).isEqualTo(10);

        // Aprobar debe descontar 3 unidades (10 -> 7) y vaciar el carrito
        mvc.perform(post("/pagos/payphone/simular")
                        .session(session)
                        .with(authentication(cliente))
                        .with(csrf())
                        .param("tx", "LC-CARRITO-OK-1")
                        .param("estado", "aprobado"))
                .andExpect(status().is3xxRedirection());

        Producto trasAprobar = productoRepository.findById(productoId).orElseThrow();
        assertThat(trasAprobar.getStock()).isEqualTo(7);
        assertThat(carritoService.obtenerResumen(clienteId).isVacio()).isTrue();
    }

    @Test
    void flujoEndToEndEcommerceDesdeCarritoHastaPedidoConfirmadoYDescuentoDeStock() throws Exception {
        UUID clienteId = UUID.randomUUID();
        var cliente = clienteAuth(clienteId, "Sofía E2E");

        String redirectProd = mvc.perform(post("/productos")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .param("nombre", "Queso Fresco")
                        .param("categoria", "LACTEOS_Y_HUEVOS")
                        .param("descripcion", "Queso de prueba E2E")
                        .param("precio", "2.50")
                        .param("stock", "5"))
                .andExpect(status().is3xxRedirection())
                .andReturn()
                .getResponse()
                .getRedirectedUrl();
        Long productoId = Long.valueOf(redirectProd.substring(redirectProd.lastIndexOf('/') + 1));

        MockHttpSession session = new MockHttpSession();
        carritoService.agregar(clienteId, productoId, 2);
        UUID intento = paymentService.obtenerIntentoCompra(null, clienteId);

        // 1. Validación del formulario de entrega al ir a PayPhone desde /carrito
        mvc.perform(post("/pagos")
                        .session(session)
                        .with(authentication(cliente))
                        .with(csrf())
                        .param("intentoCompra", intento.toString())
                        .param("direccion", "ab")
                        .param("telefono", "123"))
                .andExpect(status().isOk())
                .andExpect(view().name("cart/index"));

        // 2. Envío válido desde /carrito hacia /pagos
        mvc.perform(post("/pagos")
                        .session(session)
                        .with(authentication(cliente))
                        .with(csrf())
                        .param("intentoCompra", intento.toString())
                        .param("direccion", "Av. República E7-123")
                        .param("telefono", "0998765432")
                        .param("referencia", "Edificio azul"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/pagos"));

        // 3. Checkout muestra datos de entrega y total $5.00
        mvc.perform(get("/pagos").session(session).with(authentication(cliente)))
                .andExpect(status().isOk())
                .andExpect(view().name("payment/checkout"))
                .andExpect(content().string(allOf(
                        containsString("Queso Fresco"),
                        containsString("Av. República E7-123"),
                        containsString("0998765432"),
                        containsString("$5.00 USD"))));

        // 4. Botón PayPhone redirige a /pagos/payphone
        String redirectPayPhone = mvc.perform(post("/pagos/payphone/iniciar")
                        .session(session)
                        .with(authentication(cliente))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/pagos/payphone?tx=*"))
                .andReturn()
                .getResponse()
                .getRedirectedUrl();

        String tx = redirectPayPhone.substring(redirectPayPhone.indexOf("tx=") + 3);

        // 5. Autorizar pago (demo): descuenta stock (5 -> 3), crea el Pedido y vacía carrito
        String redirectResultado = mvc.perform(post("/pagos/payphone/simular")
                        .session(session)
                        .with(authentication(cliente))
                        .with(csrf())
                        .param("tx", tx)
                        .param("estado", "aprobado"))
                .andExpect(status().is3xxRedirection())
                .andReturn()
                .getResponse()
                .getRedirectedUrl();

        Producto actualizado = productoRepository.findById(productoId).orElseThrow();
        assertThat(actualizado.getStock()).isEqualTo(3);
        assertThat(carritoService.obtenerResumen(clienteId).isVacio()).isTrue();

        var pedidosGuardados = pedidoRepository.findAllByOrderByCreadoEnDescIdDesc();
        assertThat(pedidosGuardados).isNotEmpty();
        Pedido pedido = pedidosGuardados.get(0);
        assertThat(pedido.getDireccionEntrega()).isEqualTo("Av. República E7-123");
        assertThat(pedido.getTotal()).isEqualByComparingTo("5.00");

        // 6. Resultado muestra comprobante aprobado con data-cart-confirmed="true" y enlace al pedido
        mvc.perform(get(redirectResultado).session(session).with(authentication(cliente)))
                .andExpect(status().isOk())
                .andExpect(view().name("payment/result"))
                .andExpect(content().string(allOf(
                        containsString("data-cart-confirmed=\"true\""),
                        containsString("APROBADO"),
                        containsString("Ver resumen del pedido #" + pedido.getId()))));

        // 7. El resumen confirmado ya no muestra el botón Pagar con PayPhone
        mvc.perform(get("/carrito/confirmado/" + pedido.getId()).session(session).with(authentication(cliente)))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Pagar con PayPhone"))));
    }

    private Pedido crearPedidoDePrueba(UUID usuarioId, String nombreProducto, String precio, int cantidad)
            throws Exception {

        String redirect = mvc.perform(post("/productos")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .param("nombre", nombreProducto)
                        .param("categoria", "DESPENSA")
                        .param("descripcion", "Producto para prueba de pasarela")
                        .param("precio", precio)
                        .param("stock", "10"))
                .andExpect(status().is3xxRedirection())
                .andReturn()
                .getResponse()
                .getRedirectedUrl();

        Long productoId = Long.valueOf(redirect.substring(redirect.lastIndexOf('/') + 1));
        carritoService.agregar(usuarioId, productoId, cantidad);
        UUID intento = paymentService.obtenerIntentoCompra(null, usuarioId);
        return carritoService.confirmarPedido(
                usuarioId,
                intento,
                new DatosEntregaForm("Av. Amazonas N34-120", "0991234567", "Frente al parque")
        );
    }

    @Test
    void muestraHistorialDeComprasConPedidosDelUsuario() throws Exception {
        UUID clienteId = UUID.randomUUID();
        var cliente = clienteAuth(clienteId, "Sofía Historial");
        Pedido pedido = crearPedidoDePrueba(clienteId, "Queso Fresco", "4.00", 2);

        mvc.perform(get("/pagos/historial").with(authentication(cliente)))
                .andExpect(status().isOk())
                .andExpect(view().name("payment/history"))
                .andExpect(content().string(allOf(
                        containsString("Historial de compras"),
                        containsString("Queso Fresco"),
                        containsString("$8.00 USD"),
                        containsString("Pagado con PayPhone")
                )));

        mvc.perform(get("/historial").with(authentication(cliente)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/pagos/historial"));
    }

    private UsernamePasswordAuthenticationToken clienteAuth(UUID id, String displayName) {
        var principal = new CestaPrincipal(id, displayName);
        return UsernamePasswordAuthenticationToken.authenticated(
                principal,
                null,
                AuthorityUtils.createAuthorityList("ROLE_CUSTOMER")
        );
    }
}
