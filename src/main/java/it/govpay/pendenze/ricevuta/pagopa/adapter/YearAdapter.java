package it.govpay.pendenze.ricevuta.pagopa.adapter;

import jakarta.xml.bind.annotation.adapters.XmlAdapter;

public class YearAdapter extends XmlAdapter<String, Integer> {

	@Override
	public Integer unmarshal(String value) {
		return DataTypeAdapter.parseYear(value);
	}

	@Override
	public String marshal(Integer value) {
		return DataTypeAdapter.printYear(value);
	}
}
