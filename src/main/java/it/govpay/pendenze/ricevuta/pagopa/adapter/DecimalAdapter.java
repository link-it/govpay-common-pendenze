package it.govpay.pendenze.ricevuta.pagopa.adapter;

import java.math.BigDecimal;

import jakarta.xml.bind.annotation.adapters.XmlAdapter;

public class DecimalAdapter extends XmlAdapter<String, BigDecimal> {

	@Override
	public BigDecimal unmarshal(String value) {
		return DataTypeAdapter.parseImporto(value);
	}

	@Override
	public String marshal(BigDecimal value) {
		return DataTypeAdapter.printImporto(value);
	}
}
