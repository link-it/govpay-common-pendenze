package it.govpay.pendenze.ricevuta.pagopa.adapter;

import java.time.LocalDate;

import jakarta.xml.bind.annotation.adapters.XmlAdapter;

public class DateAdapter extends XmlAdapter<String, LocalDate>
{
	@Override
	public LocalDate unmarshal(String value) {
		return DataTypeAdapterCXF.parseLocalDate( value );
	}

	@Override
	public String marshal(LocalDate value) {
		return DataTypeAdapterCXF.printLocalDate( value );
	}
}
