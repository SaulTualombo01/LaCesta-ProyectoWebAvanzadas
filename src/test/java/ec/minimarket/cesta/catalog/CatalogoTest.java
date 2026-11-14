package ec.minimarket.cesta.catalog;

import java.math.BigDecimal;
import java.util.List;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
class CatalogoTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ProductoService productos;

    @Autowired
    private EntityManager entityManager;

    @Test
    void rechazaUnaPromocionIgualAlPrecioYStockNegativo() throws Exception {
        mvc.perform(post("/productos").with(user("admin").roles("ADMIN")).with(csrf())
                        .param("nombre", "Jugo").param("categoria", "BEBIDAS")
                        .param("descripcion", "Botella de 500 ml")
                        .param("precio", "2.25").param("precioPromocional", "2.25").param("stock", "-1"))
                .andExpect(status().isOk())
                .andExpect(view().name("catalog/form"))
                .andExpect(model().attributeHasFieldErrors("form", "precioPromocional", "stock"));
    }

    @Test
    void publicaYRetiraUnaOfertaConStockDesdeElCrud() throws Exception {
        var admin = user("admin").roles("ADMIN");
        var cliente = user("cliente").roles("CUSTOMER");
        productos.crear(producto("Jugo sin descuento", Categoria.BEBIDAS, 6, null), null);
        productos.crear(new ProductoForm("Oferta agotada", Categoria.BEBIDAS, "Descripción", new BigDecimal("2.25"),
                0, new BigDecimal("1.50"), null), null);

        String ruta = mvc.perform(post("/productos").with(admin).with(csrf())
                        .param("nombre", "Jugo en oferta").param("categoria", "BEBIDAS")
                        .param("descripcion", "Botella de 500 ml").param("precio", "2.25")
                        .param("precioPromocional", "1.50").param("stock", "6"))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        Long id = Long.valueOf(ruta.substring(ruta.lastIndexOf('/') + 1));
        String version = productos.consultar(id).getVersion().toString();

        mvc.perform(get("/ofertas").with(cliente))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("Jugo en oferta"),
                        containsString("Ahorras $0.75"),
                        containsString("$1.50"),
                        containsString("$2.25"),
                        not(containsString("Jugo sin descuento")),
                        not(containsString("Oferta agotada")))));
        mvc.perform(get("/ofertas").param("categoria", "BEBIDAS").with(cliente))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Jugo en oferta")));
        mvc.perform(get("/ofertas").param("categoria", "DESPENSA").with(cliente))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("Por ahora no hay ofertas con stock en esta categoría."),
                        not(containsString("Jugo en oferta")))));
        mvc.perform(get("/admin/ofertas").with(cliente)).andExpect(status().isForbidden());
        mvc.perform(get("/ofertas").with(admin))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/ofertas"));
        mvc.perform(get("/admin/ofertas").with(admin))
                .andExpect(content().string(not(containsString("href=\"/ofertas\""))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("oculto en Ofertas")));
        mvc.perform(post(ruta).with(cliente).with(csrf())).andExpect(status().isForbidden());

        mvc.perform(post(ruta).with(admin).with(csrf())
                        .param("nombre", "Jugo en oferta").param("categoria", "BEBIDAS")
                        .param("descripcion", "Botella de 500 ml").param("precio", "2.25").param("stock", "6")
                        .param("version", version))
                .andExpect(status().is3xxRedirection());
        mvc.perform(get("/ofertas").with(cliente))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Por ahora no hay productos en oferta con stock.")));
    }

    @Test
    void listaPorCategoriaEnPaginasDeOcho() {
        for (int i = 1; i <= 9; i++) {
            productos.crear(producto("Producto de prueba " + i, Categoria.BEBIDAS, 6, null), null);
        }
        productos.crear(producto("Producto de otra categoría", Categoria.LIMPIEZA, 6, null), null);

        var primeraPagina = productos.listar(Categoria.BEBIDAS, 0);
        var segundaPagina = productos.listar(Categoria.BEBIDAS, 1);
        assertThat(primeraPagina.getTotalElements()).isEqualTo(9);
        assertThat(primeraPagina.getContent()).hasSize(8).extracting(Producto::getCategoria)
                .containsOnly(Categoria.BEBIDAS);
        assertThat(segundaPagina.getContent()).extracting(Producto::getNombre)
                .isEqualTo(List.of("Producto de prueba 1"));
    }

    @Test
    void conservaElStockGuardadoCuandoSeIntentaEditarConUnaVersionAnterior() {
        Producto creado = productos.crear(producto("Jugo", Categoria.BEBIDAS, 6, null), null);
        Long id = creado.getId();
        Long versionAnterior = creado.getVersion();
        productos.actualizar(id, producto("Jugo", Categoria.BEBIDAS, 5, versionAnterior), null, false);
        entityManager.flush();
        entityManager.clear();

        assertThatThrownBy(() -> productos.actualizar(id, producto("Jugo", Categoria.BEBIDAS, 0, versionAnterior), null, false))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(productos.consultar(id).getStock()).isEqualTo(5);
    }

    @Test
    void guardaReemplazaYQuitaLaFotoDesdeElCrud() throws Exception {
        var cliente = user("cliente").roles("CUSTOMER");
        var administrador = user("admin").roles("ADMIN");
        String ruta = mvc.perform(multipart("/productos").file(foto(Color.RED)).with(administrador).with(csrf())
                        .param("nombre", "Jugo").param("categoria", "BEBIDAS")
                        .param("descripcion", "Botella de 500 ml").param("precio", "2.25").param("stock", "6"))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        Long id = Long.valueOf(ruta.substring(ruta.lastIndexOf('/') + 1));
        Producto original = productos.consultar(id);
        assertThat(original.getImagenRevision()).isNotNull();
        var revision = original.getImagenRevision();
        var version = original.getVersion().toString();

        mvc.perform(get(ruta + "/imagen")).andExpect(status().is3xxRedirection());
        byte[] publicada = mvc.perform(get(ruta + "/imagen").with(cliente))
                .andExpect(status().isOk()).andExpect(content().contentType("image/jpeg"))
                .andReturn().getResponse().getContentAsByteArray();
        var imagen = ImageIO.read(new ByteArrayInputStream(publicada));
        assertThat(imagen.getWidth()).isEqualTo(1200);
        assertThat(new Color(imagen.getRGB(0, 0)).getRed()).isGreaterThan(240);

        mvc.perform(multipart(ruta).file(foto(Color.BLUE)).with(administrador).with(csrf())
                        .param("nombre", "Jugo").param("categoria", "BEBIDAS")
                        .param("descripcion", "Botella de 500 ml").param("precio", "2.25")
                        .param("stock", "6").param("version", version))
                .andExpect(status().is3xxRedirection());
        Producto actualizado = productos.consultar(id);
        assertThat(actualizado.getImagenRevision()).isNotEqualTo(revision);
        assertThat(actualizado.getVersion().toString()).isNotEqualTo(version);

        mvc.perform(multipart(ruta).with(administrador).with(csrf())
                        .param("nombre", "Jugo").param("categoria", "BEBIDAS")
                        .param("descripcion", "Botella de 500 ml").param("precio", "2.25")
                        .param("stock", "6").param("version", actualizado.getVersion().toString())
                        .param("quitarImagen", "true"))
                .andExpect(status().is3xxRedirection());
        assertThat(productos.consultar(id).getImagenRevision()).isNull();
        mvc.perform(get(ruta + "/imagen").with(cliente)).andExpect(status().isNotFound());
    }

    @Test
    void rechazaUnArchivoQueSimulaSerUnaFoto() throws Exception {
        var archivo = new MockMultipartFile("imagen", "aparente.png", "image/png", new byte[]{1, 2, 3});
        mvc.perform(multipart("/productos").file(archivo).with(user("admin").roles("ADMIN")).with(csrf())
                        .param("nombre", "Jugo").param("categoria", "BEBIDAS")
                        .param("descripcion", "Botella de 500 ml").param("precio", "2.25").param("stock", "6"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("errorImagen", "Elige una imagen JPG o PNG que se pueda abrir."));
        assertThat(productos.listar(Categoria.BEBIDAS, 0).getTotalElements()).isZero();
    }

    private MockMultipartFile foto(Color color) throws Exception {
        var imagen = new BufferedImage(1600, 100, BufferedImage.TYPE_INT_RGB);
        var dibujo = imagen.createGraphics();
        try {
            dibujo.setColor(color);
            dibujo.fillRect(0, 0, imagen.getWidth(), imagen.getHeight());
        } finally {
            dibujo.dispose();
        }
        var archivo = new ByteArrayOutputStream();
        ImageIO.write(imagen, "png", archivo);
        return new MockMultipartFile("imagen", "producto.png", "image/png", archivo.toByteArray());
    }

    private ProductoForm producto(String nombre, Categoria categoria, int stock, Long version) {
        return new ProductoForm(nombre, categoria, "Descripción de prueba", new BigDecimal("2.25"),
                stock, null, version);
    }
}
