package com.easymarket.marketplace.exception;

/**
 * Excepción lanzada cuando un usuario intenta modificar o acceder a un recurso del cual no es el propietario.
 */
public class NoEsElPropietarioException extends RuntimeException {

    /**
     * Construye la excepción con un mensaje descriptivo.
     *
     * @param message mensaje descriptivo del fallo de propiedad
     */
    public NoEsElPropietarioException(String message) {
        super(message);
    }
}
