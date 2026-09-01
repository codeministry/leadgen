package de.codeministry.leadgen.web;

/** What `GET /api/status` answers. */
public record AppStatus(String application, String version) {}
