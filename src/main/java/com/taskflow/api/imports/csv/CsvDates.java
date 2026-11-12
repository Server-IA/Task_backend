package com.taskflow.api.imports.csv;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lee las fechas de un archivo CSV, que cada herramienta escribe a su manera: ISO («2026-10-14», con o sin hora), la de
 * Jira («14/Oct/26 3:45 PM»), con el mes en letras («Oct 14, 2026», «14 oct 2026»), en milisegundos (ClickUp) o con
 * barras. En las de barras no se sabe si va primero el día o el mes: se decide mirando todas las de la columna (un
 * número mayor que 12 lo dice); si ninguna lo aclara, se toma día/mes.
 */
public final class CsvDates {

    private static final Pattern SLASHED = Pattern.compile("^(\\d{1,2})[/.-](\\d{1,2})[/.-](\\d{2}|\\d{4})(?:[ T,]+(.*))?$");
    private static final DateTimeFormatter JIRA_API = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
    private static final Pattern EPOCH = Pattern.compile("^\\d{10,13}$");
    private static final List<Locale> LOCALES = List.of(Locale.ENGLISH, Locale.forLanguageTag("es"));
    private static final List<String> NAMED = List.of(
            "d/MMM/yy h:mm a", "d/MMM/yy H:mm", "d/MMM/yyyy h:mm a", "d/MMM/yyyy H:mm", "d/MMM/yy", "d/MMM/yyyy",
            "MMM d, yyyy h:mm a", "MMM d, yyyy, h:mm a", "MMM d, yyyy", "MMMM d, yyyy", "d MMM yyyy H:mm", "d MMM yyyy",
            "d MMMM yyyy", "d 'de' MMMM 'de' yyyy", "EEEE, MMMM d yyyy", "EEEE, MMMM d yyyy, h:mm:ss a");
    private static final List<String> TIMES = List.of("H:mm:ss", "H:mm", "h:mm:ss a", "h:mm a");

    private final boolean monthFirst;

    private CsvDates(boolean monthFirst) {
        this.monthFirst = monthFirst;
    }

    /** Un lector para las fechas de una o varias columnas, vistos todos sus valores. */
    public static CsvDates forValues(Collection<String> values) {
        boolean dayFirstSeen = false;
        boolean monthFirstSeen = false;
        for (String value : values) {
            Matcher matcher = SLASHED.matcher(value.strip());
            if (!matcher.matches()) {
                continue;
            }
            dayFirstSeen |= Integer.parseInt(matcher.group(1)) > 12;
            monthFirstSeen |= Integer.parseInt(matcher.group(2)) > 12;
        }
        return new CsvDates(monthFirstSeen && !dayFirstSeen);
    }

    /** @return el día, o {@code null} si el texto está vacío o no es una fecha */
    public LocalDate date(String text) {
        Instant instant = instant(text);
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC).toLocalDate();
    }

    /** @return el momento (en UTC si el texto no trae zona), o {@code null} si está vacío o no es una fecha */
    public Instant instant(String text) {
        String value = text == null ? "" : text.strip();
        if (value.isEmpty()) {
            return null;
        }
        if (EPOCH.matcher(value).matches()) {
            long number = Long.parseLong(value);
            return value.length() > 10 ? Instant.ofEpochMilli(number) : Instant.ofEpochSecond(number);
        }
        Instant iso = iso(value);
        if (iso != null) {
            return iso;
        }
        Instant slashed = slashed(value);
        return slashed != null ? slashed : named(value);
    }

    private static Instant iso(String value) {
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException notOffset) {
            // sigue
        }
        try {
            // Como las da la API de Jira: «2026-10-14T15:45:00.000+0200»
            return OffsetDateTime.parse(value, JIRA_API).toInstant();
        } catch (DateTimeParseException notJira) {
            // sigue
        }
        try {
            return LocalDateTime.parse(value.replace(' ', 'T')).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException notLocal) {
            // sigue
        }
        try {
            return LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException notDate) {
            return null;
        }
    }

    private Instant slashed(String value) {
        Matcher matcher = SLASHED.matcher(value);
        if (!matcher.matches()) {
            return null;
        }
        int first = Integer.parseInt(matcher.group(1));
        int second = Integer.parseInt(matcher.group(2));
        int year = Integer.parseInt(matcher.group(3));
        if (year < 100) {
            year += 2000;
        }
        int day = monthFirst ? second : first;
        int month = monthFirst ? first : second;
        if (month < 1 || month > 12 || day < 1 || day > 31) {
            return null;
        }
        try {
            LocalDate date = LocalDate.of(year, month, day);
            return date.atTime(time(matcher.group(4))).toInstant(ZoneOffset.UTC);
        } catch (java.time.DateTimeException invalid) {
            return null;
        }
    }

    private static java.time.LocalTime time(String text) {
        if (text == null || text.isBlank()) {
            return java.time.LocalTime.MIDNIGHT;
        }
        for (String pattern : TIMES) {
            try {
                return java.time.LocalTime.parse(text.strip().toUpperCase(Locale.ROOT), formatter(pattern, Locale.ENGLISH));
            } catch (DateTimeParseException next) {
                // sigue
            }
        }
        return java.time.LocalTime.MIDNIGHT;
    }

    private static Instant named(String value) {
        // «oct.» → «oct»; «p. m.» → «PM»
        String clean = value.replaceAll("(?i)\\b([ap])\\.?\\s?m\\.?", "$1M").replaceAll("(\\p{L})\\.", "$1");
        for (Locale locale : LOCALES) {
            for (String pattern : NAMED) {
                try {
                    TemporalAccessor parsed = formatter(pattern, locale).parse(clean);
                    LocalDate date = LocalDate.from(parsed);
                    int seconds = parsed.isSupported(ChronoField.SECOND_OF_DAY) ? parsed.get(ChronoField.SECOND_OF_DAY) : 0;
                    return date.atStartOfDay().plusSeconds(seconds).toInstant(ZoneOffset.UTC);
                } catch (java.time.DateTimeException next) {
                    // sigue
                }
            }
        }
        return null;
    }

    private static DateTimeFormatter formatter(String pattern, Locale locale) {
        return new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern).toFormatter(locale);
    }

    /** Los valores no vacíos de una lista (para {@link #forValues}). */
    public static List<String> nonBlank(Collection<String> values) {
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                result.add(value);
            }
        }
        return result;
    }
}
