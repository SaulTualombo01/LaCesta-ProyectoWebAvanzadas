package ec.minimarket.cesta.catalog;

import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.web.multipart.MultipartFile;

final class FotografiaProducto {
    private static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final long MAX_PIXELES = 24_000_000;
    private static final int LADO_MAXIMO = 1200;
    private static final String FORMATO_INVALIDO = "Elige una imagen JPG o PNG que se pueda abrir.";

    private FotografiaProducto() {
    }

    static byte[] preparar(MultipartFile archivo) {
        if (archivo == null || (archivo.isEmpty() && "".equals(archivo.getOriginalFilename()))) {
            return null;
        }
        if (archivo.getSize() > MAX_BYTES) {
            throw new IllegalArgumentException("La imagen debe pesar 5 MB o menos.");
        }
        try (var archivoEntrada = archivo.getInputStream();
             var entrada = new MemoryCacheImageInputStream(archivoEntrada)) {
            var lectores = ImageIO.getImageReaders(entrada);
            if (!lectores.hasNext()) {
                throw new IllegalArgumentException(FORMATO_INVALIDO);
            }
            ImageReader lector = lectores.next();
            try {
                return convertir(lector, entrada);
            } finally {
                lector.dispose();
            }
        } catch (IOException error) {
            throw new IllegalArgumentException(FORMATO_INVALIDO, error);
        }
    }

    private static byte[] convertir(ImageReader lector, MemoryCacheImageInputStream entrada) throws IOException {
        String formato = lector.getFormatName().toLowerCase(Locale.ROOT);
        if (!formato.equals("jpeg") && !formato.equals("png")) {
            throw new IllegalArgumentException(FORMATO_INVALIDO);
        }
        lector.setInput(entrada, true, true);
        int ancho = lector.getWidth(0);
        int alto = lector.getHeight(0);
        if ((long) ancho * alto > MAX_PIXELES) {
            throw new IllegalArgumentException("Elige una imagen de hasta 24 megapíxeles.");
        }

        int ladoMayor = Math.max(ancho, alto);
        int muestreo = Math.max(1, ladoMayor / LADO_MAXIMO);
        var parametros = lector.getDefaultReadParam();
        parametros.setSourceSubsampling(muestreo, muestreo, 0, 0);
        BufferedImage original = lector.read(0, parametros);

        double escala = Math.min(1.0, (double) LADO_MAXIMO / ladoMayor);
        int anchoFinal = Math.max(1, (int) Math.round(ancho * escala));
        int altoFinal = Math.max(1, (int) Math.round(alto * escala));
        BufferedImage salida = new BufferedImage(anchoFinal, altoFinal, BufferedImage.TYPE_INT_RGB);
        var dibujo = salida.createGraphics();
        try {
            dibujo.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            dibujo.drawImage(original, 0, 0, anchoFinal, altoFinal, new Color(249, 247, 241), null);
        } finally {
            dibujo.dispose();
        }
        var jpeg = new ByteArrayOutputStream();
        ImageIO.write(salida, "jpeg", jpeg);
        return jpeg.toByteArray();
    }
}
