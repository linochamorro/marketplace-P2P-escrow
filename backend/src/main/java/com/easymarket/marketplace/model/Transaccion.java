package com.easymarket.marketplace.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * Entidad JPA que representa una transacción de compra en escrow (Story 5 y siguientes, spec.md).
 *
 * <p>Mapea la tabla {@code transacciones} creada por la migración V7 (PHA03TSK01): una
 * transacción nace en estado {@link EstadoTransaccion#RESERVADA} con el
 * {@code precio_snapshot} inmutable tomado de la publicación en el instante de la reserva
 * (plan.md, "Flujo de compra y reserva de stock (PHA03)"). El {@code precio_snapshot} se
 * almacena como entero en centavos (constitución, principio 3: dinero como enteros, nunca
 * punto flotante). Los timestamps {@code fecha_enviado} y {@code fecha_entregado} permanecen
 * nulos hasta que la transacción transiciona a {@code enviado}/{@code entregado} (PHA04). La
 * {@code descripcion_prueba_entrega} nullable guarda exclusivamente el texto opcional de la Story
 * 6b al marcar entregado; no es el motivo de negocio de un evento de auditoría.</p>
 */
@Entity
@Table(name = "transacciones")
public class Transaccion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "comprador_id", nullable = false)
    private Usuario comprador;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "publicacion_id", nullable = false)
    private Publicacion publicacion;

    @Convert(converter = EstadoTransaccionConverter.class)
    @Column(name = "estado", nullable = false, length = 50)
    private EstadoTransaccion estado;

    @Column(name = "precio_snapshot", nullable = false)
    private long precioSnapshot;

    @Column(name = "fecha_reservada", nullable = false)
    private ZonedDateTime fechaReservada;

    @Column(name = "fecha_enviado")
    private ZonedDateTime fechaEnviado;

    @Column(name = "fecha_entregado")
    private ZonedDateTime fechaEntregado;

    @Column(name = "descripcion_prueba_entrega", columnDefinition = "TEXT")
    private String descripcionPruebaEntrega;

    @Column(name = "motivo_cancelacion", columnDefinition = "TEXT")
    private String motivoCancelacion;

    /**
     * Constructor por defecto requerido por JPA.
     */
    public Transaccion() {
    }

    /**
     * Constructor para la creación de una transacción en estado {@link EstadoTransaccion#RESERVADA}
     * con snapshot inmutable del precio de la publicación en el instante de la reserva.
     *
     * @param comprador usuario comprador (debe ser distinto al dueño de la publicación — regla
     *                  de no auto-compra, validada previamente por {@code ValidacionCompraService})
     * @param publicacion publicación adquirida
     * @param precioSnapshot precio en centavos tomado de la publicación en el instante de la reserva
     * @param fechaReservada marca temporal en que se crea la reserva (misma transacción atómica)
     */
    public Transaccion(Usuario comprador, Publicacion publicacion, long precioSnapshot, ZonedDateTime fechaReservada) {
        this.comprador = comprador;
        this.publicacion = publicacion;
        this.estado = EstadoTransaccion.RESERVADA;
        this.precioSnapshot = precioSnapshot;
        this.fechaReservada = fechaReservada;
    }

    /**
     * Obtiene el ID único de la transacción.
     *
     * @return ID de la transacción
     */
    public Long getId() {
        return id;
    }

    /**
     * Establece el ID único de la transacción.
     *
     * @param id ID de la transacción
     */
    public void setId(Long id) {
        this.id = id;
    }

    /**
     * Obtiene el usuario comprador.
     *
     * @return usuario comprador
     */
    public Usuario getComprador() {
        return comprador;
    }

    /**
     * Establece el usuario comprador.
     *
     * @param comprador usuario comprador
     */
    public void setComprador(Usuario comprador) {
        this.comprador = comprador;
    }

    /**
     * Obtiene la publicación adquirida.
     *
     * @return publicación adquirida
     */
    public Publicacion getPublicacion() {
        return publicacion;
    }

    /**
     * Establece la publicación adquirida.
     *
     * @param publicacion publicación adquirida
     */
    public void setPublicacion(Publicacion publicacion) {
        this.publicacion = publicacion;
    }

    /**
     * Obtiene el estado actual de la transacción.
     *
     * @return estado de la transacción
     */
    public EstadoTransaccion getEstado() {
        return estado;
    }

    /**
     * Establece el estado de la transacción.
     *
     * @param estado estado de la transacción
     */
    public void setEstado(EstadoTransaccion estado) {
        this.estado = estado;
    }

    /**
     * Obtiene el snapshot inmutable del precio en centavos (entero de 64 bits) tomado de la
     * publicación en el instante de la reserva.
     *
     * @return precio snapshot en centavos
     */
    public long getPrecioSnapshot() {
        return precioSnapshot;
    }

    /**
     * Establece el snapshot inmutable del precio en centavos.
     *
     * @param precioSnapshot precio snapshot en centavos
     */
    public void setPrecioSnapshot(long precioSnapshot) {
        this.precioSnapshot = precioSnapshot;
    }

    /**
     * Obtiene la fecha y hora en que se creó la reserva.
     *
     * @return marca temporal de la reserva
     */
    public ZonedDateTime getFechaReservada() {
        return fechaReservada;
    }

    /**
     * Establece la fecha y hora de la reserva.
     *
     * @param fechaReservada marca temporal de la reserva
     */
    public void setFechaReservada(ZonedDateTime fechaReservada) {
        this.fechaReservada = fechaReservada;
    }

    /**
     * Obtiene la fecha y hora de envío (poblada al transicionar a {@code enviado}, PHA04).
     *
     * @return fecha de envío, o {@code null} si la transacción aún no se envió
     */
    public ZonedDateTime getFechaEnviado() {
        return fechaEnviado;
    }

    /**
     * Establece la fecha y hora de envío.
     *
     * @param fechaEnviado fecha de envío
     */
    public void setFechaEnviado(ZonedDateTime fechaEnviado) {
        this.fechaEnviado = fechaEnviado;
    }

    /**
     * Obtiene la fecha y hora de entrega (poblada al transicionar a {@code entregado}, PHA04).
     *
     * @return fecha de entrega, o {@code null} si la transacción aún no se entregó
     */
    public ZonedDateTime getFechaEntregado() {
        return fechaEntregado;
    }

    /**
     * Establece la fecha y hora de entrega.
     *
     * @param fechaEntregado fecha de entrega
     */
    public void setFechaEntregado(ZonedDateTime fechaEntregado) {
        this.fechaEntregado = fechaEntregado;
    }

    /**
     * Obtiene la descripción opcional de la prueba de entrega registrada por el vendedor.
     *
     * @return descripción de prueba de entrega, o {@code null} cuando el vendedor no la proveyó
     */
    public String getDescripcionPruebaEntrega() {
        return descripcionPruebaEntrega;
    }

    /**
     * Establece la descripción opcional de prueba de entrega de la Story 6b.
     *
     * <p>El valor {@code null} representa que el vendedor marcó la transacción como entregada sin
     * proporcionar descripción; esta tarea no impone restricciones de longitud o contenido.</p>
     *
     * @param descripcionPruebaEntrega descripción de prueba de entrega, o {@code null} si no se
     *                                  proveyó una
     */
    public void setDescripcionPruebaEntrega(String descripcionPruebaEntrega) {
        this.descripcionPruebaEntrega = descripcionPruebaEntrega;
    }

    /**
     * Obtiene el motivo obligatorio registrado cuando la transacción fue cancelada.
     *
     * @return motivo de cancelación, o {@code null} si aún no se canceló
     */
    public String getMotivoCancelacion() {
        return motivoCancelacion;
    }

    /**
     * Establece el motivo de una cancelación validada por el servicio de dominio.
     *
     * @param motivoCancelacion motivo obligatorio de la cancelación
     */
    public void setMotivoCancelacion(String motivoCancelacion) {
        this.motivoCancelacion = motivoCancelacion;
    }

    /**
     * Compara esta transacción con otro objeto usando la identidad persistente.
     *
     * @param o objeto a comparar contra esta transacción
     * @return {@code true} si ambos objetos son la misma instancia o si son transacciones de la
     *         misma clase con el mismo ID; {@code false} en cualquier otro caso
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Transaccion that = (Transaccion) o;
        return Objects.equals(id, that.id);
    }

    /**
     * Calcula el hash basado en el ID persistente de la transacción.
     *
     * @return hash de la identidad de esta transacción
     */
    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
