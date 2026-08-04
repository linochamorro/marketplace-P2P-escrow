package com.easymarket.marketplace.model;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Conversor JPA para mapear {@link EstadoPublicacion} a su representación en cadena exacta en PostgreSQL (ej. 'pendiente_revisión').
 */
@Converter(autoApply = true)
public class EstadoPublicacionConverter implements AttributeConverter<EstadoPublicacion, String> {

    @Override
    public String convertToDatabaseColumn(EstadoPublicacion attribute) {
        if (attribute == null) {
            return null;
        }
        return attribute.getCodigo();
    }

    @Override
    public EstadoPublicacion convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return null;
        }
        return EstadoPublicacion.fromCodigo(dbData);
    }
}
