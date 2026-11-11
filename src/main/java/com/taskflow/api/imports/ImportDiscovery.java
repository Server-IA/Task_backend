package com.taskflow.api.imports;

import com.taskflow.api.imports.csv.CsvImport;
import com.taskflow.api.imports.source.ImportSource;

import java.util.List;

/**
 * Lo que se encontró en el origen al conectar o al subir el archivo, antes de leer nada a fondo.
 *
 * @param account  con qué cuenta se entró (conexión directa)
 * @param projects los proyectos que hay
 * @param csv      las columnas del archivo (solo CSV)
 */
public record ImportDiscovery(String account, List<ImportSource.Found> projects, CsvImport.Preview csv) {
}
