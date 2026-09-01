package de.codeministry.leadgen.offer;

/** One portal advertising an offer. A duplicate cluster names all of them. */
public record OfferSourceRef(String portal, String agency, String url) {}
