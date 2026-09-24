package it.govpay.pendenze.criteri;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * {@link Pageable} a scorrimento libero (offset/limit), non a pagine allineate come
 * {@link org.springframework.data.domain.PageRequest} (che ammette solo {@code offset =
 * pagina * dimensione}). Lo YAML v3 usa esplicitamente i parametri di query standardizzati
 * AGID {@code offset}/{@code limit} (RAC_REST_NAME_005), che il chiamante puo' avanzare di
 * un valore qualunque — non solo multipli di {@code limit}.
 */
public final class OffsetPageRequest implements Pageable {

    private final long offset;
    private final int limit;
    private final Sort sort;

    private OffsetPageRequest(long offset, int limit, Sort sort) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset non puo' essere negativo: " + offset);
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit deve essere maggiore di zero: " + limit);
        }
        this.offset = offset;
        this.limit = limit;
        this.sort = sort;
    }

    public static OffsetPageRequest of(long offset, int limit) {
        return new OffsetPageRequest(offset, limit, Sort.unsorted());
    }

    public static OffsetPageRequest of(long offset, int limit, Sort sort) {
        return new OffsetPageRequest(offset, limit, sort == null ? Sort.unsorted() : sort);
    }

    @Override
    public int getPageNumber() {
        return (int) (offset / limit);
    }

    @Override
    public int getPageSize() {
        return limit;
    }

    @Override
    public long getOffset() {
        return offset;
    }

    @Override
    public Sort getSort() {
        return sort;
    }

    @Override
    public Pageable next() {
        return new OffsetPageRequest(offset + limit, limit, sort);
    }

    @Override
    public Pageable previousOrFirst() {
        return hasPrevious() ? new OffsetPageRequest(Math.max(0, offset - limit), limit, sort) : first();
    }

    @Override
    public Pageable first() {
        return new OffsetPageRequest(0, limit, sort);
    }

    @Override
    public Pageable withPage(int pageNumber) {
        return new OffsetPageRequest((long) pageNumber * limit, limit, sort);
    }

    @Override
    public boolean hasPrevious() {
        return offset > 0;
    }
}
