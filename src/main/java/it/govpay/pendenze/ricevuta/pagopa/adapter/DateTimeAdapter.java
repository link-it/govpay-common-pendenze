package it.govpay.pendenze.ricevuta.pagopa.adapter;

import java.time.LocalDateTime;

import jakarta.xml.bind.annotation.adapters.XmlAdapter;

public class DateTimeAdapter extends XmlAdapter<String, LocalDateTime>
{
	@Override
	public LocalDateTime unmarshal(String value) {
		return DataTypeAdapterCXF.parseLocalDateTime(value);
	}

	@Override
	public String marshal(LocalDateTime value) {
		return DataTypeAdapterCXF.printLocalDateTime(value);
	}
}
