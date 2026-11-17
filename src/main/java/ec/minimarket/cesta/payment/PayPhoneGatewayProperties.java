package ec.minimarket.cesta.payment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PayPhoneGatewayProperties {

    private final String storeName;
    private final String currency;
    private final String referencePrefix;
    private final String apiBaseUrl;
    private final String token;
    private final String storeId;
    private final String publicUrl;

    public PayPhoneGatewayProperties(
            @Value("${APP_PAYPHONE_STORE_NAME:La Cesta Minimarket}") String storeName,
            @Value("${APP_PAYPHONE_CURRENCY:USD}") String currency,
            @Value("${APP_PAYPHONE_REFERENCE_PREFIX:LC-PED-}") String referencePrefix,
            @Value("${APP_PAYPHONE_API_URL:https://pay.payphonetodoesposible.com}") String apiBaseUrl,
            @Value("${APP_PAYPHONE_TOKEN:}") String token,
            @Value("${APP_PAYPHONE_STORE_ID:}") String storeId,
            @Value("${APP_PUBLIC_URL:https://localhost:8080}") String publicUrl) {
        this.storeName = storeName;
        this.currency = currency;
        this.referencePrefix = referencePrefix;
        this.apiBaseUrl = apiBaseUrl == null ? "" : apiBaseUrl.strip();
        this.token = token == null ? "" : token.strip();
        this.storeId = storeId == null ? "" : storeId.strip();
        this.publicUrl = publicUrl == null ? "https://localhost:8080" : publicUrl.strip();
    }

    public String getStoreName() {
        return storeName;
    }

    public String getCurrency() {
        return currency;
    }

    public String getReferencePrefix() {
        return referencePrefix;
    }

    public String getApiBaseUrl() {
        return apiBaseUrl;
    }

    public String getToken() {
        return token;
    }

    public String getStoreId() {
        return storeId;
    }

    public String getPublicUrl() {
        return publicUrl;
    }

    public boolean isLiveConfigured() {
        return !token.isBlank() && !storeId.isBlank();
    }

    public String responseUrl() {
        String base = publicUrl.endsWith("/")
                ? publicUrl.substring(0, publicUrl.length() - 1)
                : publicUrl;
        return base + "/pagos/payphone/retorno";
    }
}
