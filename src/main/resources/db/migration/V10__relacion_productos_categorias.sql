-- Eliminar la restricción de categorías estáticas.
ALTER TABLE productos
DROP CONSTRAINT productos_categoria_valida;

-- Ampliar la longitud del código de categoría.
ALTER TABLE productos
ALTER COLUMN categoria TYPE VARCHAR(50);

-- Relacionar los productos con las categorías dinámicas.
ALTER TABLE productos
    ADD CONSTRAINT fk_productos_categorias
        FOREIGN KEY (categoria)
            REFERENCES categorias (codigo)
            ON UPDATE RESTRICT
            ON DELETE RESTRICT;