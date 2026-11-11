package ec.minimarket.cesta;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

record TlsFixture(Path keyStore, SSLContext clientContext) {
    static final String PASSWORD = "tls-solo-pruebas";

    static TlsFixture create() {
        try {
            Path directory = Files.createTempDirectory("la-cesta-tls-");
            Path file = directory.resolve("localhost.p12");
            directory.toFile().deleteOnExit();
            file.toFile().deleteOnExit();
            String executable = System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool";
            Path keytool = Path.of(System.getProperty("java.home"), "bin", executable);
            Process process = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "localhost",
                    "-storetype", "PKCS12", "-keystore", file.toString(), "-storepass", PASSWORD,
                    "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-dname", "CN=localhost",
                    "-ext", "SAN=DNS:localhost,IP:127.0.0.1", "-ext", "EKU=serverAuth")
                    .redirectErrorStream(true).start();
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("No se generó a tiempo el certificado TLS de prueba.");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("No se generó el certificado TLS de prueba: "
                        + new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }

            KeyStore server = KeyStore.getInstance("PKCS12");
            try (var input = Files.newInputStream(file)) {
                server.load(input, PASSWORD.toCharArray());
            }
            KeyStore trust = KeyStore.getInstance("PKCS12");
            trust.load(null, null);
            trust.setCertificateEntry("localhost", server.getCertificate("localhost"));
            TrustManagerFactory managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            managers.init(trust);
            SSLContext client = SSLContext.getInstance("TLS");
            client.init(null, managers.getTrustManagers(), null);
            return new TlsFixture(file, client);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Se interrumpió la preparación del TLS de prueba.", failure);
        } catch (IOException | GeneralSecurityException failure) {
            throw new IllegalStateException("No se pudo preparar el TLS de prueba.", failure);
        }
    }
}

