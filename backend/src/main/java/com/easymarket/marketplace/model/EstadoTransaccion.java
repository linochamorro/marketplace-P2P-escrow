package com.easymarket.marketplace.model;

/**
 * Enumeración que representa los estados válidos de una transacción en la máquina de estados
 * del sistema (Story 5 y siguientes, spec.md).
 *
 * <p>Los códigos coinciden exactamente con los 8 valores del CHECK de la columna {@code estado}
 * de la tabla {@code transacciones} creada por la migración V7 (PHA03TSK01):
 * {@code reservada}, {@code enviado}, {@code entregado}, {@code recibido},
 * {@code recibido_sin_respuesta}, {@code disputa}, {@code cancelada}, {@code completada}.
 * Toda transacción nace en {@link #RESERVADA} (Story 5, spec.md; plan.md, "Flujo de compra y
 * reserva de stock (PHA03)").</p>
 */
public enum EstadoTransaccion {

    /**
     * Estado inicial de toda transacción: el stock queda reservado y los fondos en escrow al
     * confirmarse el pago (webhook {@code payment_intent.succeeded}).
     */
    RESERVADA("reservada"),

    /**
     * El vendedor marcó el envío del producto/servicio.
     */
    ENVIADO("enviado"),

    /**
     * El vendedor marcó la entrega (con prueba de entrega opcional).
     */
    ENTREGADO("entregado"),

    /**
     * El comprador confirmó la recepción dentro de las 48h; libera fondos y descuenta stock.
     */
    RECIBIDO("recibido"),

    /**
     * El sistema confirmó la recepción automáticamente tras 48h sin acción del comprador;
     * mismo efecto sobre fondos y stock que {@link #RECIBIDO}, solo distinguible para el admin.
     */
    RECIBIDO_SIN_RESPUESTA("recibido_sin_respuesta"),

    /**
     * El comprador reclamó dentro de las 48h; los fondos quedan congelados hasta resolución del admin.
     */
    DISPUTA("disputa"),

    /**
     * Transacción cancelada con motivo obligatorio; restaura stock y revierte fondos.
     */
    CANCELADA("cancelada"),

    /**
     * Disputa resuelta por el admin a favor del vendedor; libera fondos y acredita saldo.
     */
    COMPLETADA("completada");

    private final String codigo;

    /**
     * Construye un valor de la enumeración con su código de base de datos.
     *
     * @param codigo representación exacta en PostgreSQL (coincide con el CHECK de V7)
     */
    EstadoTransaccion(String codigo) {
        this.codigo = codigo;
    }

    /**
     * Obtiene la representación en cadena exacta requerida por la base de datos PostgreSQL.
     *
     * @return código de estado en base de datos
     */
    public String getCodigo() {
        return codigo;
    }

    /**
     * Busca el valor de la enumeración a partir de su código en base de datos.
     *
     * @param codigo código en base de datos
     * @return el valor de {@link EstadoTransaccion} correspondiente
     * @throws IllegalArgumentException si el código no coincide con ningún estado válido
     */
    public static EstadoTransaccion fromCodigo(String codigo) {
        for (EstadoTransaccion estado : values()) {
            if (estado.codigo.equals(codigo)) {
                return estado;
            }
        }
        throw new IllegalArgumentException("Estado de transacción desconocido: " + codigo);
    }
}
