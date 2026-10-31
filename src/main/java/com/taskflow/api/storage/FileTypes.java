package com.taskflow.api.storage;

import com.taskflow.api.dto.response.StorageReportResponse.FileGroup;
import com.taskflow.api.exception.BadRequestException;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Qué archivos se aceptan, con qué tipo y hasta qué tamaño. La extensión decide el tipo; el que declara el navegador debe
 * corresponderle (o venir vacío) y se guarda el tipo canónico. Todo lo que no está en la lista (ejecutables, HTML, SVG…)
 * se rechaza. El navegador valida lo mismo antes de pedir la subida.
 */
public final class FileTypes {

    public static final long MB = 1024L * 1024;
    /** Imágenes (adjuntas o pegadas) y la foto de perfil. */
    public static final long IMAGE_LIMIT = 5 * MB;
    /** Documentos y ZIP. */
    public static final long FILE_LIMIT = 10 * MB;

    public static final String NOT_ALLOWED = "Este tipo de archivo no se puede adjuntar.";
    public static final String ONLY_IMAGES = "Solo se pueden pegar imágenes PNG, JPG, GIF o WebP.";
    public static final String AVATAR_TYPES = "La foto de perfil debe ser PNG, JPG o WebP.";
    public static final String TYPE_MISMATCH = "El tipo del archivo no coincide con su extensión.";

    /**
     * @param extension   en minúsculas, sin punto
     * @param contentType el que se guarda y se firma
     * @param aliases     otros tipos con los que lo declaran los navegadores (Windows manda los CSV como Excel y los
     *                    ZIP como {@code x-zip-compressed})
     * @param signature   primeros bytes del archivo ({@code null}: sin comprobar, como en los de texto)
     */
    public record FileType(String extension, String contentType, Set<String> aliases, FileGroup group,
                           byte[][] signature) {

        public boolean isImage() {
            return group == FileGroup.IMAGES;
        }

        public long limit() {
            return isImage() ? IMAGE_LIMIT : FILE_LIMIT;
        }

        boolean accepts(String declared) {
            return declared.equals(contentType) || aliases.contains(declared);
        }

        /** Si el contenido empieza como debe (o si este tipo no se comprueba). */
        public boolean matches(byte[] head) {
            if (signature == null) {
                return true;
            }
            return Arrays.stream(signature).anyMatch(prefix -> startsWith(head, prefix));
        }
    }

    private static final byte[] ZIP = {'P', 'K', 3, 4};
    private static final byte[] EMPTY_ZIP = {'P', 'K', 5, 6};
    private static final byte[][] OFFICE = {ZIP};

    private static final List<FileType> TYPES = List.of(
            image("png", "image/png", Set.of(), new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}),
            image("jpg", "image/jpeg", Set.of("image/jpg", "image/pjpeg"), new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}),
            image("jpeg", "image/jpeg", Set.of("image/jpg", "image/pjpeg"), new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}),
            new FileType("gif", "image/gif", Set.of(), FileGroup.IMAGES, new byte[][] {ascii("GIF87a"), ascii("GIF89a")}),
            // RIFF····WEBP: se comprueba aparte (los bytes 4 a 7 son el tamaño)
            new FileType("webp", "image/webp", Set.of(), FileGroup.IMAGES, new byte[][] {ascii("RIFF")}),
            document("pdf", "application/pdf", Set.of("application/x-pdf"), new byte[][] {ascii("%PDF-")}),
            document("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", Set.of(), OFFICE),
            document("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", Set.of(), OFFICE),
            document("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation", Set.of(),
                    OFFICE),
            document("txt", "text/plain", Set.of(), null),
            document("md", "text/markdown", Set.of("text/x-markdown", "text/plain"), null),
            document("csv", "text/csv", Set.of("application/vnd.ms-excel", "text/plain", "application/csv"), null),
            new FileType("zip", "application/zip", Set.of("application/x-zip-compressed", "application/x-zip",
                    "multipart/x-zip"), FileGroup.OTHER, new byte[][] {ZIP, EMPTY_ZIP}));

    private static final Map<String, FileType> BY_EXTENSION = TYPES.stream()
            .collect(Collectors.toUnmodifiableMap(FileType::extension, Function.identity()));

    /** Tipos de la foto de perfil (sin GIF). */
    private static final Set<String> AVATAR_CONTENT_TYPES = Set.of("image/png", "image/jpeg", "image/webp");

    private FileTypes() {
    }

    /** El tipo de un archivo por su extensión, si se acepta. */
    public static Optional<FileType> byExtension(String fileName) {
        return extension(fileName).map(BY_EXTENSION::get);
    }

    /** El tipo canónico de un {@code Content-Type} (por ejemplo, de la foto de perfil). */
    public static Optional<FileType> byContentType(String contentType) {
        String declared = normalize(contentType);
        return TYPES.stream().filter(type -> type.contentType().equals(declared)).findFirst();
    }

    /**
     * Valida un archivo de una tarea: su extensión, el tipo declarado y el tamaño. {@code imagesOnly}: imágenes pegadas
     * en un texto.
     *
     * @return el tipo, con el {@code Content-Type} que se guarda y se firma
     */
    public static FileType validate(String fileName, String declaredType, long size, boolean imagesOnly) {
        FileType type = byExtension(fileName).orElse(null);
        if (type == null || (imagesOnly && !type.isImage())) {
            throw new BadRequestException(imagesOnly ? ONLY_IMAGES : NOT_ALLOWED);
        }
        String declared = normalize(declaredType);
        // Sin tipo (algunos navegadores con .md o .csv) vale el de la extensión
        if (!declared.isEmpty() && !declared.equals("application/octet-stream") && !type.accepts(declared)) {
            throw new BadRequestException(TYPE_MISMATCH);
        }
        checkSize(size, type.limit());
        return type;
    }

    /** Valida la foto de perfil (ya recortada en el navegador): PNG, JPG o WebP de hasta 5 MB. */
    public static FileType validateAvatar(String declaredType, long size) {
        String declared = normalize(declaredType);
        if (!AVATAR_CONTENT_TYPES.contains(declared)) {
            throw new BadRequestException(AVATAR_TYPES);
        }
        checkSize(size, IMAGE_LIMIT);
        return byContentType(declared).orElseThrow();
    }

    private static void checkSize(long size, long limit) {
        if (size <= 0) {
            throw new BadRequestException("El archivo está vacío.");
        }
        if (size > limit) {
            throw new BadRequestException("Pesa " + formatSize(size) + " y el límite es " + formatSize(limit) + ".");
        }
    }

    /** Grupo para la pestaña «Almacenamiento» (por el tipo guardado). */
    public static FileGroup group(String contentType) {
        return byContentType(contentType).map(FileType::group).orElse(FileGroup.OTHER);
    }

    /** Si el contenido de un archivo empieza como su tipo: un «.png» que es HTML no pasa. */
    public static boolean contentMatches(String contentType, byte[] head) {
        FileType type = byContentType(contentType).orElse(null);
        if (type == null) {
            return false;
        }
        if (!type.matches(head)) {
            return false;
        }
        return !"image/webp".equals(type.contentType()) || startsWith(Arrays.copyOfRange(head, Math.min(8, head.length),
                head.length), ascii("WEBP"));
    }

    /** Bytes que hacen falta para {@link #contentMatches}. */
    public static final int SIGNATURE_BYTES = 16;

    /**
     * Nombre seguro para guardar y para {@code Content-Disposition}: sin carpetas ni caracteres de control, y de hasta
     * 255 caracteres conservando la extensión.
     */
    public static String cleanName(String fileName) {
        String name = fileName == null ? "" : fileName;
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        name = name.replaceAll("[\\p{Cntrl}]", "").strip();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            name = "archivo";
        }
        if (name.length() > 255) {
            String extension = extension(name).map(ext -> "." + ext).orElse("");
            name = name.substring(0, 255 - extension.length()) + extension;
        }
        return name;
    }

    /** «2 GB», «2,4 MB», «86 KB», «512 B» (como en la interfaz): un decimal por debajo de 10, sin «,0». */
    public static String formatSize(long bytes) {
        if (bytes >= 1024 * MB) {
            return decimal(bytes / (double) (1024 * MB)) + " GB";
        }
        if (bytes >= MB) {
            return decimal(bytes / (double) MB) + " MB";
        }
        if (bytes >= 1024) {
            return Math.round(bytes / 1024.0) + " KB";
        }
        return bytes + " B";
    }

    private static String decimal(double value) {
        double rounded = Math.round(value * 10) / 10.0;
        if (rounded >= 10 || rounded == Math.floor(rounded)) {
            return String.valueOf(Math.round(rounded));
        }
        return String.format(Locale.ROOT, "%.1f", rounded).replace('.', ',');
    }

    private static Optional<String> extension(String fileName) {
        if (fileName == null) {
            return Optional.empty();
        }
        String name = fileName.strip();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return Optional.empty();
        }
        return Optional.of(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static String normalize(String contentType) {
        if (contentType == null) {
            return "";
        }
        int semicolon = contentType.indexOf(';');
        String type = semicolon >= 0 ? contentType.substring(0, semicolon) : contentType;
        return type.strip().toLowerCase(Locale.ROOT);
    }

    private static FileType image(String extension, String contentType, Set<String> aliases, byte[] signature) {
        return new FileType(extension, contentType, aliases, FileGroup.IMAGES, new byte[][] {signature});
    }

    private static FileType document(String extension, String contentType, Set<String> aliases, byte[][] signature) {
        return new FileType(extension, contentType, aliases, FileGroup.DOCUMENTS, signature);
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static boolean startsWith(byte[] content, byte[] prefix) {
        if (content.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (content[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
