package com.wonday.insurance.weather;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

final class RdaXml {
	private RdaXml() {}
	static Element parse(byte[] bytes) {
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
			factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
			factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
			factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
			factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
			var builder = factory.newDocumentBuilder();
			builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
			return builder.parse(new ByteArrayInputStream(bytes)).getDocumentElement();
		} catch (Exception exception) { throw WeatherException.invalid(); }
	}
	static String normalized(String text) { return text.replace("_", "").toLowerCase(java.util.Locale.ROOT); }
	static List<Element> children(Element parent, String name) {
		List<Element> result = new ArrayList<>();
		for (var child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
			if (child instanceof Element element && normalized(element.getTagName()).equals(normalized(name))) result.add(element);
		}
		return result;
	}
	static Element one(Element parent, String name) {
		var found = children(parent, name);
		if (found.size() != 1) throw WeatherException.invalid();
		return found.getFirst();
	}
	static String text(Element parent, String name) { return one(parent, name).getTextContent().strip(); }
	static int integer(Element parent, String name) {
		String value = text(parent, name);
		if (!value.matches("[0-9]{1,7}")) throw WeatherException.invalid();
		return Integer.parseInt(value);
	}
	static Page page(byte[] bytes, int pageNumber, int maximumRows, boolean allowEmpty) {
		Element root = parse(bytes);
		String code = text(one(root, "header"), "result_Code");
		if (code.equals("301") && allowEmpty) return new Page(0, List.of());
		if (List.of("500", "600", "999").contains(code)) throw WeatherException.unavailable();
		if (!code.equals("200")) throw new WeatherException(org.springframework.http.HttpStatus.BAD_GATEWAY,
			"WEATHER_PROVIDER_REJECTED", "Weather provider rejected the request; check API authorization and parameters");
		Element body = one(root, "body");
		int total = integer(body, "total_Count");
		if (integer(body, "page_No") != pageNumber || total > maximumRows) throw WeatherException.invalid();
		List<Element> items = children(one(body, "items"), "item");
		if (items.size() > total || (total > 0 && items.isEmpty())) throw WeatherException.invalid();
		return new Page(total, items);
	}
	record Page(int total, List<Element> items) {}
}
