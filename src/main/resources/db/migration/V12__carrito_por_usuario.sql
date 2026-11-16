create table carritos (
    usuario_id uuid primary key,
    intento_compra uuid not null,
    cantidades text not null default '{}'
);

alter table pedidos add column intento_compra uuid;
create unique index pedidos_intento_compra_idx on pedidos (usuario_id, intento_compra);
