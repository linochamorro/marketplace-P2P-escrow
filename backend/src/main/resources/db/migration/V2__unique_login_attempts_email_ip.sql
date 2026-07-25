-- V2__unique_login_attempts_email_ip.sql
-- Adición de constraint de unicidad en (email, ip) para prevención de condiciones de carrera en rate limiting

ALTER TABLE login_attempts ADD CONSTRAINT uk_login_attempts_email_ip UNIQUE (email, ip);
