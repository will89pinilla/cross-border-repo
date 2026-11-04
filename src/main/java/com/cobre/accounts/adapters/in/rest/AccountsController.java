package com.cobre.accounts.adapters.in.rest;

import com.cobre.accounts.application.port.in.AccountBalance;
import com.cobre.accounts.application.port.in.EntryLinePage;
import com.cobre.accounts.application.port.in.GetBalanceQuery;
import com.cobre.accounts.application.port.in.GetTransactionsQuery;
import com.cobre.accounts.domain.model.AccountId;
import com.cobre.accounts.domain.model.InvalidIdentifierException;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.List;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /v1/accounts/{account_id}/balance} and {@code /transactions} (D3, spec §8.2), over the
 * committed state; they answer during a load too. Bodies are written by {@link ApiJson}, with the
 * same line shape as the report.
 */
@RestController
public class AccountsController {

    private final GetBalanceQuery balances;
    private final GetTransactionsQuery transactions;

    public AccountsController(GetBalanceQuery balances, GetTransactionsQuery transactions) {
        this.balances = Objects.requireNonNull(balances, "balances");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public record BalanceView(
            @JsonProperty("account_id") String accountId,
            String type,
            String status,
            String currency,
            String balance) {}

    public record TransactionsView(
            @JsonProperty("account_id") String accountId,
            String currency,
            long total,
            int limit,
            int offset,
            List<LoadReport.LineView> transactions) {}

    @Operation(summary = "Balance of an account; for Cobre accounts, the sum of their lines (D10)")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = BalanceView.class)))
    @ApiResponse(responseCode = "404", description = "ACCOUNT_NOT_FOUND")
    @GetMapping(path = "/v1/accounts/{account_id}/balance", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> balance(@PathVariable("account_id") String accountId) {
        AccountBalance balance = balances.balanceOf(id(accountId))
                .orElseThrow(() -> ApiException.accountNotFound(accountId));
        return json(new BalanceView(balance.accountId().value(), balance.type().name(), balance.status().name(),
                balance.currency().value(), LoadReport.amount(balance.balance())));
    }

    @Operation(summary = "Entry lines of an account, openings included, by posted_at, source_event_id and line")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = TransactionsView.class)))
    @ApiResponse(responseCode = "400", description = "INVALID_PAGINATION")
    @ApiResponse(responseCode = "404", description = "ACCOUNT_NOT_FOUND")
    @GetMapping(path = "/v1/accounts/{account_id}/transactions", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> transactions(@PathVariable("account_id") String accountId,
            @Schema(defaultValue = "100", minimum = "1", maximum = "1000") @RequestParam(name = "limit", required = false)
                    String limit,
            @Schema(defaultValue = "0", minimum = "0") @RequestParam(name = "offset", required = false) String offset) {
        // The request is checked before anything is read, also for an unknown account.
        int pageLimit = integer(limit, 100);
        int pageOffset = integer(offset, 0);
        if (pageLimit < 1 || pageLimit > GetTransactionsQuery.MAX_LIMIT || pageOffset < 0) {
            throw ApiException.invalidPagination();
        }
        EntryLinePage page = transactions.linesOf(id(accountId), pageLimit, pageOffset)
                .orElseThrow(() -> ApiException.accountNotFound(accountId));
        List<LoadReport.LineView> lines = page.lines().stream()
                .map(line -> new LoadReport.LineView(line.sourceEventId(), line.direction().name(),
                        LoadReport.amount(line.amount()), LoadReport.instant(line.postedAt())))
                .toList();
        return json(new TransactionsView(page.accountId().value(), page.currency().value(), page.total(), page.limit(),
                page.offset(), lines));
    }

    /** An id that is not a valid account id (too long, not printable ASCII) cannot exist: 404. */
    private static AccountId id(String accountId) {
        try {
            return AccountId.of(accountId);
        } catch (InvalidIdentifierException e) {
            throw ApiException.accountNotFound(accountId);
        }
    }

    /** Absent means the default; anything but a plain integer (empty, decimal, too big) is invalid. */
    private static int integer(String value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw ApiException.invalidPagination();
        }
    }

    private static ResponseEntity<byte[]> json(Object body) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(ApiJson.write(body));
    }
}
