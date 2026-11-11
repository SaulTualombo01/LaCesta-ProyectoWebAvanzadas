package ec.minimarket.cesta.catalog;

public enum Categoria {
    FRUTAS_Y_VERDURAS("Frutas y verduras"),
    DESPENSA("Despensa"),
    LACTEOS_Y_HUEVOS("Lácteos y huevos"),
    BEBIDAS("Bebidas"),
    LIMPIEZA("Limpieza"),
    OTROS("Otros");

    private final String etiqueta;

    Categoria(String etiqueta) {
        this.etiqueta = etiqueta;
    }

    public String getEtiqueta() {
        return etiqueta;
    }
}
