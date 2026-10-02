package it.govpay.pendenze.ricevuta.pagopa.adapter;

import java.time.LocalTime;

import jakarta.xml.bind.annotation.adapters.XmlAdapter;

public class TimeAdapter extends XmlAdapter<String, LocalTime> {
	@Override
	public LocalTime unmarshal(String value) {
		return DataTypeAdapterCXF.parseLocalTime(value);
	}

	@Override
	public String marshal(LocalTime value) {
		return DataTypeAdapterCXF.printLocalTime(value);
	}
}
