package app.ai.lab.tradeEngineLite.Algos.ResultReact;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.Locale;
import java.util.Objects;

final class Dates {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // Supports: "dd/MM/yyyy" or "dd/MM/yyyy hh:mm a"
    private static final DateTimeFormatter FLEX_FMT = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("dd/MM/yyyy")
            .optionalStart()
            .appendLiteral(' ')
            .appendPattern("hh:mm a") // 12-hour clock with AM/PM
            .optionalEnd()
            // Apply ONLY when time is absent:
            .parseDefaulting(ChronoField.CLOCK_HOUR_OF_AMPM, 12) // 12 AM
            .parseDefaulting(ChronoField.AMPM_OF_DAY, 0) // AM
            .parseDefaulting(ChronoField.MINUTE_OF_HOUR, 0)
            .toFormatter(java.util.Locale.ENGLISH);

    static LocalDate parseQuarterDate(String raw) {
        Objects.requireNonNull(raw, "dateTimeRaw");
        var ldt = LocalDateTime.parse(raw.trim(), FLEX_FMT);
        return ldt.atZone(IST).toLocalDate();
    }

    /** Format as dd-MM-yy */
    static String fmtDdMmYy(LocalDate d) {
        return d == null ? null : d.format(DateTimeFormatter.ofPattern("dd-MM-yy", Locale.ENGLISH));
    }

    /** Next calendar day; if you want to skip weekends, add a simple loop here. */
    static LocalDate nextTradingDate(LocalDate d) {
        return d.plusDays(1);
    }
}
