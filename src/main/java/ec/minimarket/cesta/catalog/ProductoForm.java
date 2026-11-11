package ec.minimarket.cesta.catalog;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ProductoForm(
        @NotBlank(message = "Escribe el nombre del producto.")
        @Size(max = 100, message = "Usa hasta 100 caracteres.")
        String nombre,

        @NotNull(message = "Selecciona una categoría.")
        Categoria categoria,

        @NotBlank(message = "Escribe una descripción.")
        @Size(max = 500, message = "Usa hasta 500 caracteres.")
        String descripcion,

        @NotNull(message = "Escribe el precio regular.")
        @DecimalMin(value = "0.01", message = "El precio mínimo es 0,01.")
        @Digits(integer = 6, fraction = 2, message = "Usa hasta seis dígitos enteros y dos decimales.")
        BigDecimal precio,

        @NotNull(message = "Escribe las unidades disponibles.")
        @Min(value = 0, message = "El stock mínimo es cero.")
        @Max(value = 999999, message = "El stock máximo es 999 999.")
        Integer stock,

        @DecimalMin(value = "0.01", message = "El precio mínimo es 0,01.")
        @Digits(integer = 6, fraction = 2, message = "Usa hasta seis dígitos enteros y dos decimales.")
        BigDecimal precioPromocional,

        Long version
) {
    static ProductoForm desde(Producto producto) {
        return new ProductoForm(producto.getNombre(), producto.getCategoria(), producto.getDescripcion(),
                producto.getPrecio(), producto.getStock(), producto.getPrecioPromocional(), producto.getVersion());
    }
}
