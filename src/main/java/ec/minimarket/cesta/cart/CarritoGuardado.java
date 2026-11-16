package ec.minimarket.cesta.cart;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class CarritoGuardado {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    CarritoGuardado(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    record Estado(Map<Long, Integer> cantidades, UUID intento) {}

    Estado leer(UUID usuario) {
        return jdbc.query("select cantidades, intento_compra from la_cesta.carritos where usuario_id = ?",
                (rs, row) -> new Estado(leerCantidades(rs.getString(1)), rs.getObject(2, UUID.class)), usuario)
                .stream().findFirst().orElseGet(() -> new Estado(Map.of(), null));
    }

    Map<Long, Integer> bloquear(UUID usuario) {
        jdbc.update("insert into la_cesta.carritos (usuario_id, intento_compra) values (?, ?) on conflict do nothing",
                usuario, UUID.randomUUID());
        return leerCantidades(jdbc.queryForObject(
                "select cantidades from la_cesta.carritos where usuario_id = ? for update", String.class, usuario));
    }

    UUID intento(UUID usuario) {
        return jdbc.queryForObject("select intento_compra from la_cesta.carritos where usuario_id = ?",
                UUID.class, usuario);
    }

    void vaciar(UUID usuario) {
        jdbc.update("update la_cesta.carritos set cantidades = '{}', intento_compra = ? where usuario_id = ?",
                UUID.randomUUID(), usuario);
    }

    void guardar(UUID usuario, Map<Long, Integer> cantidades) {
        try {
            jdbc.update("update la_cesta.carritos set cantidades = ? where usuario_id = ?",
                    json.writeValueAsString(cantidades), usuario);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo guardar el carrito.", e);
        }
    }

    private LinkedHashMap<Long, Integer> leerCantidades(String contenido) {
        try {
            return json.readValue(contenido, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo leer el carrito guardado.", e);
        }
    }
}
