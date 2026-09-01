package de.codeministry.leadgen.config;

import java.util.List;

/** Every configuration problem, with the file it came from, in one message. */
public class ConfigValidationException extends RuntimeException {

    private final transient List<String> problems;

    public ConfigValidationException(String file, List<String> problems) {
        super("%s is invalid:%s".formatted(file, problems.stream().collect(java.util.stream.Collectors.joining("\n  - ", "\n  - ", ""))));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
