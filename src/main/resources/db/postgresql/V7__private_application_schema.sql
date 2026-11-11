
-- Revocar permisos del esquema
REVOKE ALL ON SCHEMA la_cesta
    FROM PUBLIC, anon, authenticated;

-- Revocar permisos de las tablas
REVOKE ALL ON ALL TABLES IN SCHEMA la_cesta
    FROM PUBLIC, anon, authenticated;

-- Revocar permisos de las secuencias
REVOKE ALL ON ALL SEQUENCES IN SCHEMA la_cesta
    FROM PUBLIC, anon, authenticated;

-- Revocar permisos por defecto de futuras tablas
ALTER DEFAULT PRIVILEGES IN SCHEMA la_cesta
REVOKE ALL ON TABLES FROM PUBLIC, anon, authenticated;

-- Revocar permisos por defecto de futuras secuencias
ALTER DEFAULT PRIVILEGES IN SCHEMA la_cesta
REVOKE ALL ON SEQUENCES FROM PUBLIC, anon, authenticated;

-- Habilitar RLS en las tablas de la aplicación
ALTER TABLE la_cesta.productos ENABLE ROW LEVEL SECURITY;
ALTER TABLE la_cesta.producto_imagen ENABLE ROW LEVEL SECURITY;
ALTER TABLE la_cesta.chat_message ENABLE ROW LEVEL SECURITY;
ALTER TABLE la_cesta.app_user ENABLE ROW LEVEL SECURITY;

-- No habilitar RLS en flyway_schema_history
