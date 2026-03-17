package br.com.liviacare.worm.query;

import java.util.Objects;

/**
 * Representa a paginação e ordenação em uma consulta.
 * Este record substitui a necessidade de uma interface Pageable e uma classe PageRequest.
 *
 * @param pageNumber O número da página (base 0).
 * @param pageSize   O tamanho da página.
 * @param sort       A ordenação a ser aplicada.
 */
public record Pageable(int pageNumber, int pageSize, Sort sort) {

    /**
     * Enum para a direção da ordenação.
     */
    public enum Direction {
        ASC, DESC
    }

    /**
     * Representa uma única instrução de ordenação.
     *
     * @param property  A propriedade pela qual ordenar.
     * @param direction A direção da ordenação (ASC ou DESC).
     */
    public record Sort(String property, Direction direction) {
        public static Sort by(String property, Direction direction) {
            return new Sort(property, direction == null ? Direction.ASC : direction);
        }

        public static Sort asc(String property) {
            return by(property, Direction.ASC);
        }

        public static Sort desc(String property) {
            return by(property, Direction.DESC);
        }
    }

    // Métodos de fábrica para Pageable

    public static Pageable of(int pageNumber, int pageSize) {
        return of(pageNumber, pageSize, null);
    }

    public static Pageable of(int pageNumber, int pageSize, Sort sort) {
        int p = Math.max(0, pageNumber);
        int s = Math.max(1, pageSize);
        // Garante uma ordenação padrão se nenhuma for fornecida.
        return new Pageable(p, s, sort == null ? Sort.asc("id") : sort);
    }

    public static Pageable ofSize(int pageSize) {
        return of(0, pageSize, null);
    }

    // Construtor canônico para validação
    public Pageable {
        Objects.requireNonNull(sort, "A ordenação (Sort) não pode ser nula.");
    }

    // Getters públicos (gerados pelo record)

    public long getOffset() {
        return (long) pageNumber * pageSize;
    }
}
