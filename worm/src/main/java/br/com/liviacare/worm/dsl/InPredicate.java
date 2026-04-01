package br.com.liviacare.worm.dsl;

import java.util.List;
import java.util.Objects;

public final class InPredicate<T> implements Predicate {

    private final Path<T> path;
    private final List<T> values;

    InPredicate(Path<T> path, List<T> values) {
        this.path = Objects.requireNonNull(path, "path cannot be null");
        this.values = Objects.requireNonNull(values, "values cannot be null");
    }

    Path<T> path() {
        return path;
    }

    List<T> values() {
        return values;
    }

    @Override
    public int shapeHash() {
        int h = 31;
        h = 31 * h + path.shapeHash();
        h = 31 * h + values.size();
        if (!values.isEmpty() && values.get(0) != null) {
            h = 31 * h + values.get(0).getClass().getName().hashCode();
        }
        return h;
    }
}

