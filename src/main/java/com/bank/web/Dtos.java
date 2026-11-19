package com.bank.web;

import com.bank.model.Customer;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public final class Dtos {
    private Dtos() {}

    public record RegisterRequest(
            @NotBlank @Size(max = 80) String fullName,
            @NotBlank @Email String email,
            @Pattern(regexp = "^$|^[0-9+\\- ]{7,15}$", message = "must be a valid phone number") String phone,
            @NotBlank @Size(min = 8, max = 64, message = "must be 8-64 characters") String password,
            String accountType,
            @PositiveOrZero BigDecimal openingDeposit) {}

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {}

    public record AddBeneficiaryRequest(
            @NotBlank @Pattern(regexp = "\\d{10}", message = "must be a 10-digit account number") String accountNumber,
            @Size(max = 40) String nickname) {}

    public record CustomerTransferRequest(@NotBlank String fromAccount, @NotBlank String beneficiaryId,
                                          @NotNull @Positive BigDecimal amount, @Size(max = 100) String note) {}

    public record TellerTransferRequest(@NotBlank String fromAccount, @NotBlank String toAccount,
                                        @NotNull @Positive BigDecimal amount, @Size(max = 100) String note) {}

    public record CustomerView(String customerId, String fullName, String email, String phone) {
        static CustomerView of(Customer c) {
            return new CustomerView(c.customerId(), c.fullName(), c.email(), c.phone());
        }
    }

    public record LoginResponse(String token, CustomerView customer) {}

    public record AccountSummary(String accountNumber, String holderName, String accountType, BigDecimal balance) {}
}
