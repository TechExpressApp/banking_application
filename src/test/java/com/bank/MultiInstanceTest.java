package com.bank;

import com.bank.repo.BankRepository;
import com.bank.service.BankException;
import com.bank.service.BankService;
import com.bank.service.SessionService;
import com.bank.store.CsvTableStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Two server instances sharing one store (as on Vercel with a Google Sheet) must not lose each other's writes. */
class MultiInstanceTest {

    @TempDir Path dir;

    private BankService instance(BankRepository[] out) throws Exception {
        var encoder = new BCryptPasswordEncoder(4);
        BankRepository repo = new BankRepository(new CsvTableStore(dir), encoder, "Password@123");
        Method load = BankRepository.class.getDeclaredMethod("load");
        load.setAccessible(true);
        load.invoke(repo);
        out[0] = repo;
        return new BankService(repo, encoder, e -> { });
    }

    @Test
    void writesFromOneInstanceAreSeenByTheOther() throws Exception {
        BankRepository[] ra = new BankRepository[1], rb = new BankRepository[1];
        BankService a = instance(ra);
        BankService b = instance(rb);           // both loaded the same starting data

        a.transfer("1000000001", "1000000002", new BigDecimal("1000"), "T", "");   // on instance A
        b.transfer("1000000001", "1000000003", new BigDecimal("500"), "T", "");    // B must see A's change first

        BankService fresh = instance(new BankRepository[1]);
        assertEquals(new BigDecimal("23500.00"), fresh.account("1000000001").balance(), "neither transfer was lost");
        assertEquals(new BigDecimal("19500.50"), fresh.account("1000000002").balance());
        assertEquals(new BigDecimal("72500.00"), fresh.account("1000000003").balance());
        assertEquals(2, fresh.recentTransactions(10).size());
    }

    @Test
    void registrationOnOneInstanceCanLogInOnTheOther() throws Exception {
        BankService a = instance(new BankRepository[1]);
        BankService b = instance(new BankRepository[1]);
        a.register("New Person", "new@example.com", "", "secret123", "SAVINGS", BigDecimal.TEN);
        assertEquals("new@example.com", b.authenticate("new@example.com", "secret123").email());
    }

    @Test
    void signedTokensWorkAcrossInstancesAndRejectTampering() {
        String secret = "0123456789abcdef0123456789abcdef";
        SessionService a = new SessionService(secret), b = new SessionService(secret);
        String token = a.create("CUST001");
        assertEquals("CUST001", b.requireCustomer("Bearer " + token));

        String forged = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("CUST002." + (System.currentTimeMillis() / 1000 + 3600)).getBytes()) + token.substring(token.indexOf('.'));
        assertThrows(BankException.class, () -> b.requireCustomer("Bearer " + forged));
        assertThrows(BankException.class, () -> new SessionService("another-secret-another-secret-xx").requireCustomer("Bearer " + token));
        assertThrows(BankException.class, () -> b.requireCustomer(null));

        a.invalidate("Bearer " + token);
        assertThrows(BankException.class, () -> a.requireCustomer("Bearer " + token));
    }
}
