package com.easymarket.marketplace.service;

/**
 * Órdenes explícitos admitidos para el listado de publicaciones aprobadas.
 */
public enum OrdenListadoPublicaciones {

    /** Ordena por precio entero en centavos de menor a mayor. */
    PRECIO_ASCENDENTE,

    /** Ordena por precio entero en centavos de mayor a menor. */
    PRECIO_DESCENDENTE,

    /** Ordena por cantidad descendente de transacciones completadas de la publicación. */
    MAS_VENDIDO
}
