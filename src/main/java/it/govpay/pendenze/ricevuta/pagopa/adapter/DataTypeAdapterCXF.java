package it.govpay.pendenze.ricevuta.pagopa.adapter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;

public final class DataTypeAdapterCXF {
	private static final String PATTERN_OFFSET = ".*([+-]\\d{2}:\\d{2}|Z)$";
	private static final DateTimeFormatter LOCAL_DATE_TIME_FORMATTER;
	static {
		LOCAL_DATE_TIME_FORMATTER = new DateTimeFormatterBuilder()
				.parseCaseInsensitive()
				.append(DateTimeFormatter.ISO_LOCAL_DATE)
				.appendLiteral('T')
				.appendValue(ChronoField.HOUR_OF_DAY, 2)
				.appendLiteral(':')
				.appendValue(ChronoField.MINUTE_OF_HOUR, 2)
				.optionalStart()
				.appendLiteral(':')
				.appendValue(ChronoField.SECOND_OF_MINUTE, 2)
				.toFormatter();
	}

	private DataTypeAdapterCXF() {
	}

	public static LocalDate parseLocalDate(String value) {
		if (value == null || value.isEmpty()) {
			return null;
		}

		// Verifica se la stringa termina con un offset nel formato +HH:mm o -HH:mm
		if (value.matches(PATTERN_OFFSET)) {
			// Rimuove l'offset se presente
			value = value.replaceFirst("([+-]\\d{2}:\\d{2}|Z)$", "");
		}

		// La stringa non contiene offset: uso ISO_LOCAL_DATE
		return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE);
	}

	public static String printLocalDate(LocalDate value) {
		return value != null ? DateTimeFormatter.ISO_LOCAL_DATE.format(value) : null;
	}

	public static LocalTime parseLocalTime(String value) {
		if (value == null || value.isEmpty()) {
			return null;
		}

		// Verifica se la stringa termina con un offset nel formato +HH:mm o -HH:mm
		if (value.matches(PATTERN_OFFSET)) {
			// La stringa contiene offset: uso ISO_OFFSET_TIME
			OffsetTime odt = OffsetTime.parse(value, DateTimeFormatter.ISO_OFFSET_TIME);
			return odt.toLocalTime();
		} else {
			// La stringa non contiene offset: uso ISO_LOCAL_TIME
			return LocalTime.parse(value, DateTimeFormatter.ISO_LOCAL_TIME);
		}
	}

	public static String printLocalTime(LocalTime value) {
		return value != null ? DateTimeFormatter.ISO_LOCAL_TIME.format(value) : null;
	}

	public static LocalDateTime parseLocalDateTime(String value) {
		if (value == null || value.isEmpty()) {
			return null;
		}

		// Verifica se la stringa termina con un offset nel formato +HH:mm o -HH:mm
		if (value.matches(PATTERN_OFFSET)) {
			// La stringa contiene offset: uso ISO_OFFSET_DATE_TIME
			OffsetDateTime odt = OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
			return odt.toLocalDateTime();
		} else {
			// La stringa non contiene offset: uso ISO_LOCAL_DATE_TIME
			return LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
		}
	}

	public static String printLocalDateTime(LocalDateTime value) {
		return value != null ? LOCAL_DATE_TIME_FORMATTER.format(value) : null;
	}
}
