package com.multi.vidulum.cashflow.infrastructure;

import com.multi.vidulum.cashflow.api.CashFlowModuleApi;
import com.multi.vidulum.cashflow.api.Type;
import com.multi.vidulum.cashflow.api.error.CashFlowNotFoundException;
import com.multi.vidulum.cashflow.api.error.CategoryAlreadyExistsException;
import com.multi.vidulum.cashflow.api.view.CashFlowSkeletonView;
import com.multi.vidulum.cashflow.api.view.CategoryNodeView;
import com.multi.vidulum.cashflow.domain.CashFlow;
import com.multi.vidulum.cashflow.api.HistoricalCashChange;
import com.multi.vidulum.common.CashChangeId;
import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.CategoryName;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.UserId;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Cashflow answering its own contract over REST.
 *
 * <p>The other half of the proof that {@link CashFlowModuleApi} says nothing about transport: it
 * answers the same three methods as {@code CashFlowModuleApiImpl} by calling the endpoints a
 * browser calls. Selected by {@code vidulum.cashflow.api.transport=http}; the in-process
 * implementation is the default, and the two are mutually exclusive beans.
 *
 * <p><b>Why this class ships with cashflow rather than with its consumer.</b> The thing it knows is
 * cashflow's own wire format - which URLs exist, that a category arrives as
 * {@code {"categoryName":{"name":"..."}}}, that the status is {@code CashFlowStatus} by name. That
 * knowledge belongs to whoever owns the endpoints. The arrangement it replaces had it backwards:
 * {@code bank_data_ingestion} declared a port, hand-rolled the request and response records for
 * somebody else's API, and pinned {@code ?isImport=true} into a URL string - so the producer could
 * not change its own payload without breaking a consumer it could not see.
 *
 * <p><b>What this is not.</b> It is not a path worth taking inside one JVM. It costs a loopback
 * round trip through the whole filter chain per call, and the endpoints are
 * {@code .authenticated()}, so it only works where an {@code Authorization} header can be
 * forwarded - which {@link CashFlowModuleApiConfig} does from the incoming request, meaning a
 * caller on a thread with no request context gets 403. Running on this transport inside the
 * monolith is therefore a deliberate test of the seam, not a deployment choice; the default is
 * in-process for exactly that reason.
 *
 * <p>Package-private, like {@code CashFlowModuleApiImpl}: {@code ModuleBoundariesArchTest} requires
 * it of every contract implementation, and nothing needs it otherwise - the factory that builds it
 * and the test that drives it both sit in this package.
 */
@Slf4j
class HttpCashFlowModuleApi implements CashFlowModuleApi {

    private final RestClient restClient;

    HttpCashFlowModuleApi(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public CashFlowSkeletonView skeletonOf(CashFlowId cashFlowId) {
        CashFlowSkeletonResponse response = fetch(cashFlowId)
                .orElseThrow(() -> new CashFlowNotFoundException(cashFlowId));

        return new CashFlowSkeletonView(
                cashFlowId,
                UserId.of(response.userId()),
                phaseOf(response.status()),
                response.activePeriod(),
                response.startPeriod(),
                nodesOf(response.inflowCategories(), null),
                nodesOf(response.outflowCategories(), null));
    }

    @Override
    public boolean exists(CashFlowId cashFlowId) {
        return fetch(cashFlowId).isPresent();
    }

    @Override
    public void createCategory(CashFlowId cashFlowId,
                               CategoryName categoryName,
                               CategoryName parentCategoryName,
                               Type type,
                               boolean isImport) {
        try {
            restClient.post()
                    .uri("/cash-flow/cf={cashFlowId}/category?isImport={isImport}", cashFlowId.id(), isImport)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new CreateCategoryRequest(
                            parentCategoryName == null ? null : parentCategoryName.name(),
                            categoryName.name(),
                            type))
                    .retrieve()
                    .toBodilessEntity();

        } catch (HttpClientErrorException e) {
            throw translate(e, cashFlowId, categoryName);
        }
    }

    @Override
    public CashChangeId importHistorical(CashFlowId cashFlowId, HistoricalCashChange change) {
        try {
            String id = restClient.post()
                    .uri("/cash-flow/cf={cashFlowId}/import-historical", cashFlowId.id())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ImportHistoricalRequest(
                            change.categoryName().name(),
                            change.name(),
                            change.description(),
                            change.money(),
                            change.type(),
                            change.dueDate(),
                            change.paidDate(),
                            change.selfTransfer(),
                            change.bankTransactionId()))
                    .retrieve()
                    .body(String.class);

            return CashChangeId.of(id);

        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new CashFlowNotFoundException(cashFlowId);
            }
            // Everything else the server refuses here is one of the five date rules, and each is a
            // BusinessException with its own ErrorCode - but reconstructing which one from a 400
            // would mean parsing the body. A caller that needs to tell them apart asks
            // CashFlowSkeletonView.importWindow() first, where the refusal arrives as the typed
            // exception itself; that is the point of the rule being a value rather than a response.
            log.error("HTTP error importing a transaction into cash flow [{}]: {}",
                    cashFlowId.id(), e.getMessage());
            throw e;
        }
    }

    /**
     * {@code GET /cash-flow/cf={id}}, with a missing cash flow turned into an empty result rather
     * than an exception, because {@link #exists} is one of the two callers and would otherwise be
     * manufacturing a boolean out of a 404.
     */
    private Optional<CashFlowSkeletonResponse> fetch(CashFlowId cashFlowId) {
        try {
            return Optional.ofNullable(restClient.get()
                    .uri("/cash-flow/cf={cashFlowId}", cashFlowId.id())
                    .retrieve()
                    .body(CashFlowSkeletonResponse.class));

        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    /**
     * Turns a status code back into the contract's own failure.
     *
     * <p>This is the half of the boundary that the old port got wrong. {@code ErrorHttpHandler}
     * serialises a {@link com.multi.vidulum.common.error.BusinessException} with its
     * {@code ErrorCode}, so the code on the wire is the one the server raised - and reading it is
     * how the exception survives the trip. The port it replaces matched on
     * {@code getResponseBodyAsString().contains("already exists")}.
     */
    private RuntimeException translate(HttpClientErrorException e, CashFlowId cashFlowId, CategoryName categoryName) {
        if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
            return new CashFlowNotFoundException(cashFlowId);
        }
        if (e.getStatusCode() == HttpStatus.CONFLICT) {
            return new CategoryAlreadyExistsException(categoryName);
        }
        log.error("HTTP error creating category [{}] in cash flow [{}]: {}",
                categoryName.name(), cashFlowId.id(), e.getMessage());
        return e;
    }

    /**
     * Exhaustive on purpose, like the one in {@code CashFlowModuleApiImpl}: adding a status to the
     * aggregate must stop compiling in both places rather than surprise a consumer at runtime. The
     * duplication is two five-line switches; sharing them would mean making one of the two
     * implementations publicly nameable, which is the thing the contract exists to prevent.
     */
    private static CashFlowSkeletonView.LifecyclePhase phaseOf(CashFlow.CashFlowStatus status) {
        return switch (status) {
            case SETUP -> CashFlowSkeletonView.LifecyclePhase.SETUP;
            case OPEN -> CashFlowSkeletonView.LifecyclePhase.OPEN;
            case CLOSED -> CashFlowSkeletonView.LifecyclePhase.CLOSED;
        };
    }

    private static List<CategoryNodeView> nodesOf(List<CategoryResponse> categories, CategoryName parentName) {
        if (categories == null) {
            return List.of();
        }
        return categories.stream()
                .map(category -> new CategoryNodeView(
                        category.categoryName(),
                        parentName,
                        category.archived(),
                        nodesOf(category.subCategories(), category.categoryName())))
                .toList();
    }

    // ============ Wire format ============

    /**
     * The skeleton fields of {@code CashFlowSummaryJson}, and nothing else.
     *
     * <p>{@code @JsonIgnoreProperties} is doing real work here: that response also carries every
     * cash change, the bank account and three timestamps. Naming only what the skeleton needs is
     * what lets the server add a field without this breaking - and it is the same payload waste
     * that made {@code skeletonOf} worth having, visible from the other side.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CashFlowSkeletonResponse(
            String userId,
            CashFlow.CashFlowStatus status,
            YearMonth activePeriod,
            YearMonth startPeriod,
            List<CategoryResponse> inflowCategories,
            List<CategoryResponse> outflowCategories
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CategoryResponse(
            CategoryName categoryName,
            boolean archived,
            List<CategoryResponse> subCategories
    ) {
    }

    /**
     * Mirrors {@code CashFlowDto.ImportHistoricalCashChangeJson} field for field - and notice that
     * it carries {@link Money} and {@link ZonedDateTime}, not a flattened amount and a bare date.
     * The endpoint always accepted these; it was the port in front of it that degraded them.
     */
    private record ImportHistoricalRequest(
            String category,
            String name,
            String description,
            Money money,
            Type type,
            ZonedDateTime dueDate,
            ZonedDateTime paidDate,
            boolean selfTransfer,
            String bankTransactionId
    ) {
    }

    /**
     * Mirrors {@code CashFlowDto.CreateCategoryJson} field for field - {@code category}, not
     * {@code categoryName}, which is the kind of detail that argues for this class living next to
     * the controller it talks to.
     */
    private record CreateCategoryRequest(
            String parentCategoryName,
            String category,
            Type type
    ) {
    }
}
