-- V8__create_processed_stripe_events_idempotency_keys.sql
-- Migración para PHA03TSK02: tablas de idempotencia.
--
-- 1) processed_stripe_events — guardia de idempotencia de webhooks de Stripe
--    (constitución, principio 4 enmendado: "reintentos del proveedor no duplican
--    efectos en el sistema"; plan.md, sección "Idempotencia de webhooks Stripe").
--    Antes de procesar un evento se intenta el INSERT con el event_id provisto por
--    Stripe; si viola la PK, el evento ya fue procesado y se descarta sin
--    reprocesar. El INSERT y la transición de estado ocurren en la misma
--    transacción atómica (constitución, principio 1).
--
-- 2) idempotency_keys — idempotencia de compra por doble-submit (plan.md, secciones
--    "Idempotencia de compra (doble-submit)" y "Flujo de compra y reserva de stock
--    (PHA03)"). La key (UUID v4 generada por el frontend al montar el botón de
--    comprar) se inserta en POST /compras; payment_intent_id se puebla en ese mismo
--    request tras crear el PaymentIntent; transaccion_id se puebla recién cuando el
--    webhook payment_intent.succeeded crea la transacción — por eso ambas columnas
--    son NULLable (una FK con valor NULL es válida en PostgreSQL).

CREATE TABLE processed_stripe_events (
    event_id VARCHAR(255) NOT NULL PRIMARY KEY,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE idempotency_keys (
    key VARCHAR(36) NOT NULL PRIMARY KEY,
    transaccion_id BIGINT CONSTRAINT fk_idempotency_keys_transaccion REFERENCES transacciones(id),
    timestamp TIMESTAMP WITH TIME ZONE NOT NULL,
    payment_intent_id VARCHAR(255)
);

-- Índice para la búsqueda del webhook por payment_intent_id: al llegar
-- payment_intent.succeeded, el servicio de webhook debe localizar la fila de la key
-- (insertada por POST /compras) por su payment_intent_id para poblar transaccion_id
-- (plan.md, "Flujo de compra y reserva de stock (PHA03)"). La key en sí se busca por
-- PK; no se necesita índice extra para eso. transaccion_id no se indexa: se puebla
-- una sola vez por el webhook y no es criterio de búsqueda del flujo.
CREATE INDEX idx_idempotency_keys_payment_intent_id ON idempotency_keys (payment_intent_id);
