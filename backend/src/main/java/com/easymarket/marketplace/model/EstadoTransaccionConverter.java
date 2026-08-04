package com.easymarket.marketplace.model;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Conversor JPA para mapear {@link EstadoTransaccion} a su representación en cadena exacta en
 * PostgreSQL (ej. 'recibido_sin_respuesta'), siguiendo el patrón de
 * {@link EstadoPublicacionConverter}.
 */
@Converter(autoApply = true)
public class EstadoTransaccionConverter implements AttributeConverter<EstadoTransaccion, String> {

    /**
     * Convierte un valor de dominio {@link EstadoTransaccion} al código exacto almacenado en la
     * columna {@code estado} de la tabla {@code transacciones}.
     *
     * @param attribute estado de transacción a persistir; puede ser {@code null} para que JPA
     *                  propague el valor nulo cuando corresponda
     * @return código de base de datos asociado al estado, o {@code null} si el atributo recibido
     *         es {@code null}
     */
    @Override
    public String convertToDatabaseColumn(EstadoTransaccion attribute) {
        if (attribute == null) {
            return null;
        }
        return attribute.getCodigo();
    }

    /**
     * Convierte el código almacenado en la base de datos al enum de dominio
     * {@link EstadoTransaccion}.
     *
     * @param dbData valor textual leído desde la columna {@code estado}; puede ser {@code null} o
     *               blanco si la fila aún no tiene estado materializado por JPA
     * @return estado de dominio correspondiente, o {@code null} cuando {@code dbData} es
     *         {@code null} o blanco
     * @throws IllegalArgumentException si {@code dbData} no coincide con ningún estado válido de la
     *         máquina de estados de transacciones
     */
    @Override
    public EstadoTransaccion convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return null;
        }
        return EstadoTransaccion.fromCodigo(dbData);
    }
}
