package com.taskflow.api.util;

/**
 * Calcula el {@code sort_order} de una tarjeta del Kanban.
 * <p>
 * Las tarjetas se separan por {@link #GAP}. Al mover una tarjeta entre otras dos se le asigna el promedio
 * de ambas, así solo se actualiza una fila. Si el espacio entre dos tarjetas se vuelve demasiado pequeño
 * (después de muchos movimientos en el mismo lugar), hay que renumerar la columna.
 */
public final class SortOrderCalculator {

    /** Separación entre tarjetas consecutivas. */
    public static final double GAP = 65_535d;

    /** Por debajo de esta distancia el promedio pierde precisión y conviene renumerar. */
    private static final double MIN_DISTANCE = 1e-6;

    private SortOrderCalculator() {
    }

    /**
     * @param above sort_order de la tarjeta que queda arriba, o {@code null} si va de primera
     * @param below sort_order de la tarjeta que queda abajo, o {@code null} si va de última
     * @param maxInColumn mayor sort_order de la columna destino, o {@code null} si está vacía
     */
    public static double between(Double above, Double below, Double maxInColumn) {
        if (above != null && below != null) {
            return (above + below) / 2;
        }
        if (above != null) {
            return above + GAP;
        }
        if (below != null) {
            return below - GAP;
        }
        return endOfColumn(maxInColumn);
    }

    /** Posición para agregar una tarjeta al final de la columna. */
    public static double endOfColumn(Double maxInColumn) {
        return maxInColumn == null ? GAP : maxInColumn + GAP;
    }

    /** Posición de la tarjeta número {@code index} (desde 0) al renumerar una columna. */
    public static double positionAt(int index) {
        return (index + 1) * GAP;
    }

    public static boolean needsRebalance(double above, double below) {
        return below - above < MIN_DISTANCE;
    }
}
