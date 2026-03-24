package br.com.liviacare.worm.orm.exception;

import java.util.List;

public class SchemaValidationException extends RuntimeException {
    private final List<String> issues;

    public SchemaValidationException(List<String> issues) {
        super("Schema validation failed with " + issues.size() + " issue(s):\n" + String.join("\n", issues));
        this.issues = List.copyOf(issues);
    }

    public List<String> getIssues() { return issues; }
}

