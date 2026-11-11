alter table productos add column imagen_revision uuid;

create table producto_imagen (
    producto_id bigint primary key references productos(id) on delete cascade,
    contenido bytea not null
);
