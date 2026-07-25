package com.easymarket.marketplace.model;

/**
 * Roles de usuario permitidos en la plataforma EasyMarket.
 *
 * <ul>
 *   <li>{@link #USUARIO}: Usuario estándar con capacidad de publicar productos y comprar.</li>
 *   <li>{@link #ADMIN}: Administrador único del sistema con permisos de moderación y gestión.</li>
 * </ul>
 */
public enum Rol {
    /**
     * Rol de usuario estándar (comprador y vendedor).
     */
    USUARIO,

    /**
     * Rol de administrador del sistema.
     */
    ADMIN
}
