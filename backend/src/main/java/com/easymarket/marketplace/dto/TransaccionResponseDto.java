package com.easymarket.marketplace.dto;

import com.easymarket.marketplace.model.Transaccion;

import java.time.ZonedDateTime;

/**
 * DTO de respuesta HTTP para las consultas de transacciones (PHA06TSK06; plan.md, "Lectura de
 * transacciones").
 *
 * <p>Sirve a los tres endpoints de lectura: {@code GET /transacciones/compras},
 * {@code GET /transacciones/ventas} y {@code GET /transacciones/{id}}. Expone exactamente los
 * campos que consume el panel de transacción del frontend ({@code PanelTransaccion}: {@code id},
 * {@code estado}, {@code precioSnapshot} y las tres fechas opcionales) más la descripción textual
 * de la publicación adquirida, que es el único contenido textual de una {@code Publicacion}
 * (entidad y migración V5 no tienen nombre/título). El {@code estado} se serializa con
 * {@code EstadoTransaccion.getCodigo()} (String en minúscula: {@code reservada}, {@code enviado},
 * ...), que es el literal que consume {@code PanelTransaccion}, no {@code name()} del enum.</p>
 *
 * <p>Se omiten deliberadamente: {@code descripcionPruebaEntrega} y {@code motivoCancelacion}
 * (textos internos de respaldo/auditoría que el panel no renderiza), los IDs y correos del
 * comprador/vendedor (la identidad del actor es implícita del principal autenticado; los correos
 * de contrapartes no son necesarios para el panel), {@code publicacionId} (el panel no lo usa), y
 * cualquier hash, cookie o dato de pago — el contrato de plan.md exige "sin exponer hashes, cookies
 * ni datos de pago". El dinero se expresa siempre como entero en centavos
 * ({@code precioSnapshot} es {@code long}, constitution principio 3).</p>
 *
 * @param id identificador persistente de la transacción
 * @param estado código de estado en minúscula ({@code EstadoTransaccion.getCodigo()})
 * @param precioSnapshot precio inmutable en centavos tomado de la publicación al reservar
 * @param fechaReservada instante en que se creó la reserva
 * @param fechaEnviado instante en que el vendedor marcó el envío, o {@code null} si aún no ocurrió
 * @param fechaEntregado instante en que el vendedor marcó la entrega, o {@code null} si aún no ocurrió
 * @param publicacionDescripcion descripción literal de la publicación adquirida
 */
public record TransaccionResponseDto(
        Long id,
        String estado,
        long precioSnapshot,
        ZonedDateTime fechaReservada,
        ZonedDateTime fechaEnviado,
        ZonedDateTime fechaEntregado,
        String publicacionDescripcion
) {
    /**
     * Mapea una entidad JPA {@link Transaccion} a su representación DTO de respuesta.
     *
     * <p>Requiere un contexto transaccional activo en el invocador porque resuelve las
     * asociaciones {@code LAZY} {@code transaccion.getPublicacion().getDescripcion()} para el
     * campo {@code publicacionDescripcion}; el endpoint garantiza ese contexto con
     * {@code @Transactional(readOnly = true)}.</p>
     *
     * @param transaccion entidad JPA de origen
     * @return DTO {@link TransaccionResponseDto} resultante
     */
    public static TransaccionResponseDto fromEntity(Transaccion transaccion) {
        return new TransaccionResponseDto(
                transaccion.getId(),
                transaccion.getEstado().getCodigo(),
                transaccion.getPrecioSnapshot(),
                transaccion.getFechaReservada(),
                transaccion.getFechaEnviado(),
                transaccion.getFechaEntregado(),
                transaccion.getPublicacion().getDescripcion()
        );
    }
}
