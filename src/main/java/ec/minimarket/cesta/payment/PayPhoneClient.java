package ec.minimarket.cesta.payment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class PayPhoneClient {

    private final RestClient http;
    private final PayPhoneGatewayProperties config;

    public PayPhoneClient(RestClient.Builder builder, PayPhoneGatewayProperties config) {
        this.config = config;
        URI origin = URI.create(config.getApiBaseUrl());
        boolean loopback = "localhost".equalsIgnoreCase(origin.getHost())
                || "127.0.0.1".equals(origin.getHost());
        if (!"https".equalsIgnoreCase(origin.getScheme()) && !loopback) {
            throw new IllegalArgumentException("APP_PAYPHONE_API_URL debe usar HTTPS.");
        }
        var transport = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build());
        transport.setReadTimeout(Duration.ofSeconds(10));
        this.http = builder.requestFactory(transport)
                .baseUrl(origin.toString())
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public PrepareResponse prepare(PaymentSummary summary, String clientTransactionId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", summary.montoCentavos());
        body.put("amountWithoutTax", summary.montoCentavos());
        body.put("amountWithTax", 0);
        body.put("tax", 0);
        body.put("service", 0);
        body.put("tip", 0);
        body.put("currency", summary.moneda());
        body.put("storeId", config.getStoreId());
        body.put("clientTransactionId", clientTransactionId);
        body.put("reference", summary.referencia());
        body.put("responseUrl", config.responseUrl());
        body.put("cancellationUrl", config.responseUrl() + "?cancelado=true&clientTransactionId=" + clientTransactionId);

        try {
            PrepareResponse response = http.post()
                    .uri("/api/button/Prepare")
                    .headers(headers -> headers.setBearerAuth(config.getToken()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(PrepareResponse.class);
            if (response == null || response.payWithPayPhone() == null || response.payWithPayPhone().isBlank()) {
                throw new PayPhoneException("PayPhone no devolvió una URL de redirección válida.");
            }
            return response;
        } catch (RestClientResponseException ex) {
            throw new PayPhoneException("PayPhone rechazó la preparación del pago (HTTP "
                    + ex.getStatusCode().value() + ").");
        } catch (RestClientException ex) {
            throw new PayPhoneException("No se pudo conectar con el servicio de PayPhone.");
        }
    }

    public ConfirmResponse confirm(long id, String clientTransactionId) {
        Map<String, Object> body = Map.of(
                "id", id,
                "clientTxId", clientTransactionId
        );
        try {
            ConfirmResponse response = http.post()
                    .uri("/api/button/V2/Confirm")
                    .headers(headers -> headers.setBearerAuth(config.getToken()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(ConfirmResponse.class);
            if (response == null) {
                throw new PayPhoneException("PayPhone no devolvió respuesta de confirmación.");
            }
            return response;
        } catch (RestClientResponseException ex) {
            throw new PayPhoneException("PayPhone rechazó la confirmación (HTTP "
                    + ex.getStatusCode().value() + ").");
        } catch (RestClientException ex) {
            throw new PayPhoneException("No se pudo confirmar la transacción con PayPhone.");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PrepareResponse(
            String paymentId,
            String payWithPayPhone,
            String payWithCard
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ConfirmResponse(
            int statusCode,
            String transactionStatus,
            long transactionId,
            String authorizationCode,
            String clientTransactionId,
            long amount,
            String message
    ) {
        public boolean isApproved() {
            return statusCode == 3 || "Approved".equalsIgnoreCase(transactionStatus);
        }
    }

    public static final class PayPhoneException extends RuntimeException {
        public PayPhoneException(String message) {
            super(message);
        }
    }
}
