package com.bank.web;

import com.bank.model.Beneficiary;
import com.bank.model.Transaction;
import com.bank.service.BankService;
import com.bank.service.SessionService;
import com.bank.web.Dtos.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class BankController {

    private final BankService bank;
    private final SessionService sessions;

    public BankController(BankService bank, SessionService sessions) {
        this.bank = bank;
        this.sessions = sessions;
    }

    // ---------- auth ----------

    @PostMapping("/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> register(@Valid @RequestBody RegisterRequest r) {
        var reg = bank.register(r.fullName(), r.email(), r.phone(), r.password(), r.accountType(), r.openingDeposit());
        return Map.of("customer", CustomerView.of(reg.customer()), "account", reg.account());
    }

    @PostMapping("/auth/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest r) {
        var c = bank.authenticate(r.email(), r.password());
        return new LoginResponse(sessions.create(c.customerId()), CustomerView.of(c));
    }

    @PostMapping("/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth) {
        sessions.invalidate(auth);
    }

    // ---------- logged-in customer ----------

    @GetMapping("/me")
    public Map<String, Object> me(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth) {
        String id = sessions.requireCustomer(auth);
        return Map.of("customer", CustomerView.of(bank.customer(id)), "accounts", bank.accountsOf(id));
    }

    @GetMapping("/me/beneficiaries")
    public List<Beneficiary> beneficiaries(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth) {
        return bank.beneficiaries(sessions.requireCustomer(auth));
    }

    @PostMapping("/me/beneficiaries")
    @ResponseStatus(HttpStatus.CREATED)
    public Beneficiary addBeneficiary(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                                      @Valid @RequestBody AddBeneficiaryRequest r) {
        return bank.addBeneficiary(sessions.requireCustomer(auth), r.accountNumber(), r.nickname());
    }

    @DeleteMapping("/me/beneficiaries/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeBeneficiary(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                                  @PathVariable String id) {
        bank.removeBeneficiary(sessions.requireCustomer(auth), id);
    }

    @PostMapping("/me/transfers")
    public Transaction transferToBeneficiary(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                                             @Valid @RequestBody CustomerTransferRequest r) {
        String id = sessions.requireCustomer(auth);
        return bank.transferToBeneficiary(id, r.fromAccount(), r.beneficiaryId(), r.amount(), r.note());
    }

    @GetMapping("/me/transactions")
    public List<Transaction> myTransactions(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth) {
        return bank.transactionsOf(sessions.requireCustomer(auth), 50);
    }

    // ---------- bank-wide transfer tab (any account -> any account) ----------

    @GetMapping("/accounts")
    public List<AccountSummary> accounts() {
        return bank.allAccounts().stream()
                .map(a -> new AccountSummary(a.accountNumber(), a.holderName(), a.accountType(), a.balance()))
                .toList();
    }

    @PostMapping("/transfers")
    public Transaction transfer(@Valid @RequestBody TellerTransferRequest r) {
        return bank.transfer(r.fromAccount(), r.toAccount(), r.amount(), "TELLER", r.note());
    }

    @GetMapping("/transactions")
    public List<Transaction> recent() {
        return bank.recentTransactions(50);
    }
}
