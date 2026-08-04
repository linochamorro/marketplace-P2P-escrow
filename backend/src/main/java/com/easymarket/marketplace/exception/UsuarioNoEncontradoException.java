package com.easymarket.marketplace.exception;

/**
 * Excepción lanzada cuando no se encuentra un usuario en el sistema por su identificador.
 */
public class UsuarioNoEncontradoException extends RuntimeException {

    /**
     * Construye una nueva excepción con el mensaje de error especificado.
     *
     * @param message mensaje descriptivo del fallo por usuario inexistente
     */
    public UsuarioNoEncontradoException(String message) {
        super(message);
    }
}
