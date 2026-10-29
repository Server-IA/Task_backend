-- Esqueletos de la descripción de las plantillas por defecto, ahora que la descripción se edita con formato
-- (Markdown): solo la estructura, sin texto de relleno («…»), y la historia de usuario como la cita
-- «**Como** … **quiero** … **para** …» que el frontend dibuja como tarjeta. Mismos textos que DefaultTemplates.java.
--
-- Solo cambian las plantillas cuyo esqueleto sigue siendo exactamente el que puso V11: si el project manager lo
-- editó (aunque sea un carácter), se respeta. Las tareas ya creadas no cambian.

UPDATE issue_templates
SET description = E'## Pasos para reproducir\n1.\n\n## Resultado esperado\n\n## Resultado actual\n\n## Entorno\n- Navegador:\n- Sistema operativo:\n- Versión:',
    updated_at  = now()
WHERE description = E'## Pasos para reproducir\n1. …\n2. …\n\n## Resultado esperado\n…\n\n## Resultado actual\n…\n\n## Entorno\n- Navegador: …\n- Sistema operativo: …\n- Versión: …';

UPDATE issue_templates
SET description = E'> **Como**\n> **quiero**\n> **para**',
    updated_at  = now()
WHERE description = 'Como … quiero … para …';

UPDATE issue_templates
SET description = E'## Objetivo\n\n## Detalles técnicos',
    updated_at  = now()
WHERE description = E'## Objetivo\n…\n\n## Detalles técnicos\n…';
