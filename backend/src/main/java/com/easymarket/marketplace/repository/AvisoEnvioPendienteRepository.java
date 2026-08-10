package com.easymarket.marketplace.repository;

import com.easymarket.marketplace.model.AvisoEnvioPendiente;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for the persistent one-time pending-shipment warning markers.
 */
@Repository
public interface AvisoEnvioPendienteRepository extends JpaRepository<AvisoEnvioPendiente, Long> {
}
