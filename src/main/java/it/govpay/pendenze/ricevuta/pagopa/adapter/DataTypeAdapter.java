package it.govpay.pendenze.ricevuta.pagopa.adapter;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.SignStyle;
import java.time.temporal.ChronoField;

public class DataTypeAdapter {

	/**
	 * {@code xs:gYear}: quattro o piu' cifre, segno opzionale, fuso opzionale
	 * ({@code Z} oppure {@code ±HH:mm}). E' la forma su cui e' legato
	 * {@link YearAdapter} in {@code global.xjb}.
	 */
	private static final DateTimeFormatter G_YEAR = new DateTimeFormatterBuilder()
			.appendValue(ChronoField.YEAR, 4, 9, SignStyle.NORMAL)
			.optionalStart()
			.appendOffsetId()
			.optionalEnd()
			.toFormatter();

	private DataTypeAdapter() {}

	public static BigDecimal parseImporto(String value) {
		return new BigDecimal(value);
	}

	public static String printImporto(BigDecimal value) {
		DecimalFormatSymbols custom=new DecimalFormatSymbols();
		custom.setDecimalSeparator('.');

		DecimalFormat format = new DecimalFormat();
		format.setDecimalFormatSymbols(custom);
		format.setGroupingUsed(false);
		format.setMaximumFractionDigits(2);
		format.setMinimumFractionDigits(2);
		return format.format(value);
	}

	public static Integer parseYear(String year) {
		if (year == null) {
			return null;
		}
		try {
			return G_YEAR.parse(year).get(ChronoField.YEAR);
		} catch (DateTimeParseException e) {
			// L'implementazione precedente passava il valore a un parser di xs:date,
			// quindi accettava anche una data completa: tolleranza conservata perche'
			// i documenti reali la sfruttano.
			return DataTypeAdapterCXF.parseLocalDate(year).getYear();
		}
	}

	public static String printYear(Integer year) {
		if (year == null) {
			return null;
		}
		return year.toString();
	}
}
