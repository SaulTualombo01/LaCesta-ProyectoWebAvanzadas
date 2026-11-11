package db.demo;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V6__demo_product_images extends BaseJavaMigration {
    private static final List<String> PRODUCTOS = List.of(
            "Papel de cocina", "Aceite de girasol", "Detergente líquido", "Huevos de campo",
            "Jugo de naranjilla", "Arroz de grano largo", "Café lojano", "Plátano verde",
            "Yogur de mora", "Pan de yuca", "Leche entera", "Tomate riñón");

    @Override
    public void migrate(Context context) throws Exception {
        var conexion = context.getConnection();
        try (var actualizar = conexion.prepareStatement("""
                    update la_cesta.productos set imagen_revision = ?, version = version + 1
                    where id = ? and nombre = ? and imagen_revision is null
                    """);
             var insertar = conexion.prepareStatement("""
                    insert into la_cesta.producto_imagen (producto_id, contenido) values (?, ?)
                    """)) {
            for (int i = 0; i < PRODUCTOS.size(); i++) {
                int id = i + 1;
                actualizar.setObject(1, UUID.randomUUID());
                actualizar.setLong(2, id);
                actualizar.setString(3, PRODUCTOS.get(i));
                // Conserva las fichas de demostración que el usuario haya renombrado.
                if (actualizar.executeUpdate() == 1) {
                    insertar.setLong(1, id);
                    insertar.setBytes(2, cargar(id));
                    insertar.executeUpdate();
                }
            }
        }
    }

    private byte[] cargar(int id) throws IOException {
        String ruta = "/db/demo/productos/" + id + ".jpg";
        try (var recurso = getClass().getResourceAsStream(ruta)) {
            if (recurso == null) {
                throw new FileNotFoundException(ruta);
            }
            return recurso.readAllBytes();
        }
    }
}
