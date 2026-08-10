-- V11__protect_movimientos_saldo_append_only.sql
-- Migración para PHA04TSK23 (Stories 6c, 6e, 9 y 12; constitution, principio 2).
--
-- El ledger conserva sus créditos y débitos como nuevas filas. PostgreSQL impide
-- modificar o eliminar movimientos ya insertados para aplicar esa auditoría
-- append-only fuera de la disciplina de cualquier capa de aplicación.
CREATE FUNCTION rechazar_modificacion_movimiento_saldo()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Los movimientos de saldo son append-only y no pueden modificarse ni eliminarse'
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER trg_movimientos_saldo_append_only
BEFORE UPDATE OR DELETE ON movimientos_saldo
FOR EACH ROW
EXECUTE FUNCTION rechazar_modificacion_movimiento_saldo();
