package ec.minimarket.cesta.cart;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.Serializable;

public record DatosEntregaForm(
        @NotBlank(message = "Ingresa la dirección de entrega.")
        @Size(min = 5, max = 180, message = "La dirección debe tener entre 5 y 180 caracteres.")
        String direccion,

        @NotBlank(message = "Ingresa un número de teléfono de contacto.")
        @Pattern(
                regexp = "^[0-9+()\\-\\s]{7,15}$",
                message = "Ingresa un teléfono válido (7 a 15 dígitos)."
        )
        String telefono,

        @Size(max = 250, message = "La referencia no debe superar los 250 caracteres.")
        String referencia
) implements Serializable {

    public static DatosEntregaForm vacio() {
        return new DatosEntregaForm("", "", "");
    }

    public DatosEntregaForm normalizado() {
        return new DatosEntregaForm(
                direccion == null ? "" : direccion.strip(),
                telefono == null ? "" : telefono.strip(),
                referencia == null ? "" : referencia.strip()
        );
    }
}
