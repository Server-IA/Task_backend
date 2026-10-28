package com.taskflow.api.entity.enums;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;

/**
 * Color de un proyecto: una clave de una paleta fija y no un hex libre. Cada color necesita varios tonos (la pastilla
 * con el identificador y su tinta, iguales en los dos temas, y una versión suave para el tema claro y otra para el
 * oscuro); con una paleta, el frontend tiene esos tonos ya elegidos para que siempre se lean bien, y se pueden
 * retocar sin migrar datos. Pastilla (fondo / tinta):
 * <ul>
 *   <li>{@code TEAL} #2EC4A0 / #0B2A22</li>
 *   <li>{@code AMBER} #FFC145 / #3A2A00</li>
 *   <li>{@code BLUE} #8CCBFF / #0B2540</li>
 *   <li>{@code PINK} #FFA6CB / #3D0F24</li>
 *   <li>{@code VIOLET} #B6A6FF / #1C1240</li>
 *   <li>{@code ORANGE} #F6A35C / #3A1E05</li>
 *   <li>{@code GREEN} #9BE7B4 / #0E3A1E</li>
 * </ul>
 * El orden importa: es el de la paleta que usaba el frontend (ver {@code V21__project_color.sql}).
 */
public enum ProjectColor {
    TEAL,
    AMBER,
    BLUE,
    PINK,
    VIOLET,
    ORANGE,
    GREEN;

    /**
     * Color para un proyecto nuevo sin color elegido: el menos usado entre los del workspace y, entre los empatados,
     * el primero de la paleta. Así los primeros siete proyectos tienen colores distintos.
     *
     * @param used colores de los proyectos que ya existen (con repeticiones)
     */
    public static ProjectColor leastUsed(Collection<ProjectColor> used) {
        Map<ProjectColor, Integer> counts = new EnumMap<>(ProjectColor.class);
        for (ProjectColor color : values()) {
            counts.put(color, 0);
        }
        for (ProjectColor color : used) {
            if (color != null) {
                counts.merge(color, 1, Integer::sum);
            }
        }
        ProjectColor best = TEAL;
        for (ProjectColor color : values()) {
            if (counts.get(color) < counts.get(best)) {
                best = color;
            }
        }
        return best;
    }
}
