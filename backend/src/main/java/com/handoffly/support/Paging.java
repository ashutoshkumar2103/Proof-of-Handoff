package com.handoffly.support;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Support lists keep the client's page and size but choose their own sort: a caller-chosen sort
 * could order by any field (including ones that must never be exposed, such as password hashes),
 * and the size is capped so one request cannot pull the whole table.
 */
public final class Paging {

    public static final int MAX_PAGE_SIZE = 100;

    private Paging() {}

    public static Pageable of(Pageable requested, Sort sort) {
        return PageRequest.of(requested.getPageNumber(), Math.min(requested.getPageSize(), MAX_PAGE_SIZE), sort);
    }
}
