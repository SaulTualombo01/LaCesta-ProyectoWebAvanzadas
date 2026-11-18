package ec.minimarket.cesta.cart;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequestMapping("/carrito/mapa")
class MapaDireccionController {

    static final double QUITO_MIN_LAT = -0.4500;
    static final double QUITO_MAX_LAT = 0.0800;
    static final double QUITO_MIN_LNG = -78.6200;
    static final double QUITO_MAX_LNG = -78.2800;
    private static final double QUITO_CENTER_LAT = -0.180653;
    private static final double QUITO_CENTER_LNG = -78.467838;
    private static final int MAX_DIRECCION = 175;
    private static final String USER_AGENT = "LaCestaMinimarket/1.0 (Quito, Ecuador)";

    private final RestClient http;

    MapaDireccionController(RestClient.Builder builder) {
        var transport = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(4))
                        .build()
        );
        transport.setReadTimeout(Duration.ofSeconds(6));
        this.http = builder.requestFactory(transport)
                .defaultHeader("User-Agent", USER_AGENT)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("Accept-Language", "es")
                .build();
    }

    @GetMapping("/buscar")
    List<SugerenciaMapa> buscar(@RequestParam String q) {
        String consulta = q == null ? "" : q.strip();
        if (consulta.length() < 3) {
            return List.of();
        }
        if (consulta.length() > 120) {
            consulta = consulta.substring(0, 120);
        }

        List<SugerenciaMapa> resultados = buscarEnPhoton(consulta);
        if (resultados.isEmpty()) {
            resultados = buscarEnNominatim(consulta);
        }
        return resultados;
    }

    @GetMapping("/reversa")
    RespuestaReversa reversa(@RequestParam double lat, @RequestParam double lon) {
        if (!dentroDeQuito(lat, lon)) {
            return new RespuestaReversa("", lat, lon, false);
        }

        String direccion = reversaEnNominatim(lat, lon);
        if (direccion.isBlank()) {
            direccion = reversaEnPhoton(lat, lon);
        }
        if (direccion.isBlank()) {
            direccion = String.format(Locale.US, "Ubicación en Quito (%.5f, %.5f)", lat, lon);
        }
        return new RespuestaReversa(acortar(direccion), lat, lon, true);
    }

    private List<SugerenciaMapa> buscarEnPhoton(String consulta) {
        try {
            URI uri = UriComponentsBuilder.fromUriString("https://photon.komoot.io/api/")
                    .queryParam("q", consulta)
                    .queryParam("lat", QUITO_CENTER_LAT)
                    .queryParam("lon", QUITO_CENTER_LNG)
                    .queryParam("bbox", String.format(
                            Locale.US, "%.4f,%.4f,%.4f,%.4f",
                            QUITO_MIN_LNG, QUITO_MIN_LAT, QUITO_MAX_LNG, QUITO_MAX_LAT))
                    .queryParam("limit", 8)
                    .build()
                    .encode()
                    .toUri();

            JsonNode root = http.get().uri(uri).retrieve().body(JsonNode.class);
            if (root == null || !root.has("features")) {
                return List.of();
            }

            List<SugerenciaMapa> lista = new ArrayList<>();
            Set<String> vistos = new LinkedHashSet<>();
            for (JsonNode feature : root.path("features")) {
                JsonNode coords = feature.path("geometry").path("coordinates");
                if (coords.size() < 2) {
                    continue;
                }
                double lon = coords.get(0).asDouble();
                double lat = coords.get(1).asDouble();
                if (!dentroDeQuito(lat, lon)) {
                    continue;
                }
                String etiqueta = formatearPropiedadesPhoton(feature.path("properties"));
                if (etiqueta.isBlank()) {
                    continue;
                }
                String clave = etiqueta.toLowerCase(Locale.ROOT);
                if (vistos.add(clave)) {
                    lista.add(new SugerenciaMapa(acortar(etiqueta), lat, lon));
                }
                if (lista.size() >= 6) {
                    break;
                }
            }
            return lista;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private List<SugerenciaMapa> buscarEnNominatim(String consulta) {
        try {
            String queryCompleta = consulta.toLowerCase(Locale.ROOT).contains("quito")
                    ? consulta
                    : consulta + ", Quito, Ecuador";
            URI uri = UriComponentsBuilder.fromUriString("https://nominatim.openstreetmap.org/search")
                    .queryParam("format", "json")
                    .queryParam("q", queryCompleta)
                    .queryParam("viewbox", String.format(
                            Locale.US, "%.4f,%.4f,%.4f,%.4f",
                            QUITO_MIN_LNG, QUITO_MAX_LAT, QUITO_MAX_LNG, QUITO_MIN_LAT))
                    .queryParam("bounded", 1)
                    .queryParam("countrycodes", "ec")
                    .queryParam("addressdetails", 1)
                    .queryParam("limit", 6)
                    .build()
                    .encode()
                    .toUri();

            JsonNode root = http.get().uri(uri).retrieve().body(JsonNode.class);
            if (root == null || !root.isArray()) {
                return List.of();
            }

            List<SugerenciaMapa> lista = new ArrayList<>();
            for (JsonNode item : root) {
                double lat = item.path("lat").asDouble(Double.NaN);
                double lon = item.path("lon").asDouble(Double.NaN);
                if (Double.isNaN(lat) || Double.isNaN(lon) || !dentroDeQuito(lat, lon)) {
                    continue;
                }
                String direccion = formatearNominatim(item);
                if (!direccion.isBlank()) {
                    lista.add(new SugerenciaMapa(acortar(direccion), lat, lon));
                }
            }
            return lista;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private String reversaEnNominatim(double lat, double lon) {
        try {
            URI uri = UriComponentsBuilder.fromUriString("https://nominatim.openstreetmap.org/reverse")
                    .queryParam("format", "json")
                    .queryParam("lat", String.format(Locale.US, "%.6f", lat))
                    .queryParam("lon", String.format(Locale.US, "%.6f", lon))
                    .queryParam("zoom", 18)
                    .queryParam("addressdetails", 1)
                    .build()
                    .toUri();

            JsonNode root = http.get().uri(uri).retrieve().body(JsonNode.class);
            if (root == null) {
                return "";
            }
            return formatearNominatim(root);
        } catch (Exception ex) {
            return "";
        }
    }

    private String reversaEnPhoton(double lat, double lon) {
        try {
            URI uri = UriComponentsBuilder.fromUriString("https://photon.komoot.io/reverse")
                    .queryParam("lat", String.format(Locale.US, "%.6f", lat))
                    .queryParam("lon", String.format(Locale.US, "%.6f", lon))
                    .build()
                    .toUri();

            JsonNode root = http.get().uri(uri).retrieve().body(JsonNode.class);
            if (root == null || !root.has("features") || root.path("features").isEmpty()) {
                return "";
            }
            return formatearPropiedadesPhoton(root.path("features").get(0).path("properties"));
        } catch (Exception ex) {
            return "";
        }
    }

    private String formatearPropiedadesPhoton(JsonNode props) {
        Set<String> partes = new LinkedHashSet<>();
        agregarSiTieneTexto(partes, props.path("name").asText(""));
        String calle = props.path("street").asText("").strip();
        String numero = props.path("housenumber").asText("").strip();
        if (!calle.isBlank()) {
            agregarSiTieneTexto(partes, numero.isBlank() ? calle : calle + " " + numero);
        }
        agregarSiTieneTexto(partes, props.path("district").asText(""));
        agregarSiTieneTexto(partes, props.path("locality").asText(""));
        String ciudad = props.path("city").asText("").strip();
        agregarSiTieneTexto(partes, ciudad.isBlank() ? "Quito" : ciudad);
        return String.join(", ", partes);
    }

    private String formatearNominatim(JsonNode item) {
        JsonNode addr = item.path("address");
        if (addr.isObject() && !addr.isEmpty()) {
            Set<String> partes = new LinkedHashSet<>();
            agregarSiTieneTexto(partes, addr.path("amenity").asText(""));
            agregarSiTieneTexto(partes, addr.path("shop").asText(""));
            String calle = addr.path("road").asText("").strip();
            String numero = addr.path("house_number").asText("").strip();
            if (!calle.isBlank()) {
                agregarSiTieneTexto(partes, numero.isBlank() ? calle : calle + " " + numero);
            }
            agregarSiTieneTexto(partes, addr.path("neighbourhood").asText(""));
            agregarSiTieneTexto(partes, addr.path("suburb").asText(""));
            String ciudad = addr.path("city").asText("").strip();
            if (ciudad.isBlank()) {
                ciudad = addr.path("town").asText("").strip();
            }
            agregarSiTieneTexto(partes, ciudad.isBlank() ? "Quito" : ciudad);
            if (!partes.isEmpty()) {
                return String.join(", ", partes);
            }
        }
        return item.path("display_name").asText("").strip();
    }

    private void agregarSiTieneTexto(Set<String> destino, String valor) {
        if (valor != null) {
            String limpio = valor.strip();
            if (!limpio.isBlank()) {
                destino.add(limpio);
            }
        }
    }

    private boolean dentroDeQuito(double lat, double lon) {
        return lat >= QUITO_MIN_LAT && lat <= QUITO_MAX_LAT
                && lon >= QUITO_MIN_LNG && lon <= QUITO_MAX_LNG;
    }

    private String acortar(String texto) {
        String limpio = texto == null ? "" : texto.strip();
        if (limpio.length() <= MAX_DIRECCION) {
            return limpio;
        }
        return limpio.substring(0, MAX_DIRECCION).strip();
    }

    record SugerenciaMapa(String displayName, double lat, double lon) {
    }

    record RespuestaReversa(String displayName, double lat, double lon, boolean enCobertura) {
    }
}
