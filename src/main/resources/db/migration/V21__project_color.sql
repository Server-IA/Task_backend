-- Color del proyecto: una clave de la paleta fija de ProjectColor.java (el frontend tiene los tonos de cada una para
-- el tema claro y el oscuro)
ALTER TABLE projects ADD COLUMN color VARCHAR(12);

-- Los proyectos que ya existen conservan el color que el frontend les calculaba con su identificador: hash de 31
-- sobre los caracteres en enteros de 32 bits (como `(hash * 31 + código) | 0` en JavaScript), valor absoluto y
-- módulo 7 sobre la paleta en este orden.
DO $$
DECLARE
    palette CONSTANT TEXT[] := ARRAY ['TEAL', 'AMBER', 'BLUE', 'PINK', 'VIOLET', 'ORANGE', 'GREEN'];
    project RECORD;
    hash    BIGINT;
BEGIN
    FOR project IN SELECT id, identifier FROM projects LOOP
        hash := 0;
        FOR i IN 1..length(project.identifier) LOOP
            hash := (hash * 31 + ascii(substr(project.identifier, i, 1))) % 4294967296;
            IF hash < 0 THEN
                hash := hash + 4294967296;
            END IF;
            IF hash >= 2147483648 THEN
                hash := hash - 4294967296;
            END IF;
        END LOOP;
        UPDATE projects SET color = palette[abs(hash) % 7 + 1] WHERE id = project.id;
    END LOOP;
END $$;

ALTER TABLE projects ALTER COLUMN color SET NOT NULL;

ALTER TABLE projects ADD CONSTRAINT ck_projects_color
    CHECK (color IN ('TEAL', 'AMBER', 'BLUE', 'PINK', 'VIOLET', 'ORANGE', 'GREEN'));
