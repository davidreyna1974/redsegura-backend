-- BSRCH-02: búsqueda insensible a acentos además de mayúsculas (RNF de búsqueda).
-- `unaccent` es una extensión "trusted" desde PostgreSQL 13 (no requiere superusuario).
CREATE EXTENSION IF NOT EXISTS unaccent;

-- unaccent(text) es STABLE; se envuelve en una función IMMUTABLE (diccionario y esquema fijados)
-- para poder usarla de forma estable en las consultas y, a futuro, en un índice pg_trgm.
CREATE OR REPLACE FUNCTION f_unaccent(text)
    RETURNS text
    LANGUAGE sql
    IMMUTABLE
    PARALLEL SAFE
    STRICT
AS $func$
    SELECT public.unaccent('public.unaccent', $1)
$func$;
