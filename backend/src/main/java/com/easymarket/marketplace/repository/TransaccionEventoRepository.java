package com.easymarket.marketplace.repository;

import com.easymarket.marketplace.model.TransaccionEvento;
import com.easymarket.marketplace.model.EstadoTransaccion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repositorio JPA para insertar eventos append-only de transacciones.
 *
 * <p>La tabla subyacente impide {@code UPDATE} y {@code DELETE} mediante el trigger de V10; este
 * repositorio se usa en los servicios de dominio para insertar el evento junto a su transición de
 * estado dentro de una única transacción.</p>
 */
@Repository
public interface TransaccionEventoRepository extends JpaRepository<TransaccionEvento, Long> {

    /**
     * Finds the most recent append-only transition that entered a transaction into one state.
     *
     * <p>The daily open-transaction notification job uses this read for {@code disputa}, whose
     * entry timestamp has no column in {@code transacciones}. Its caller already retains the
     * transaction row lock, so the state and its event history are observed atomically.</p>
     *
     * @param transaccionId transaction whose event history is inspected
     * @param estadoDestino state entered by the event to locate
     * @return latest matching append-only event, or empty when no such transition exists
     */
    Optional<TransaccionEvento> findFirstByTransaccion_IdAndEstadoDestinoOrderByCreatedAtDesc(
        Long transaccionId, EstadoTransaccion estadoDestino);
}
