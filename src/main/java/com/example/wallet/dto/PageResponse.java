package com.example.wallet.dto;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * A page of results.
 *
 * @param content       the items on this page
 * @param page          zero-based page number
 * @param size          requested page size
 * @param totalElements total number of items across all pages
 * @param totalPages    total number of pages
 * @param <T>           the item type
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
    /**
     * Converts a Spring Data page.
     *
     * @param p   the page
     * @param <T> the item type
     * @return the response DTO
     */
    public static <T> PageResponse<T> from(Page<T> p) {
        return new PageResponse<>(p.getContent(), p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
    }
}
