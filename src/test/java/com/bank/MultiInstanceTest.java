package com.bank;

import com.bank.service.BankException;
import com.bank.service.BankService;
import com.bank.service.SessionService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/** Several server instances sharing one database (as on Vercel with Supabase). */
class MultiInstanceTest {

    @Test
    void writesFromOneInstanceAreSeenByTheOther() {
        TestBank tb = TestBank.create();
        BankService a = tb.bank, b = tb.anotherInstance().bank;
        a.transfer("1000000001", "1000000002", new BigDecimal("1000"), "T", "");
        b.transfer("1000000001", "1000000003", new BigDecimal("500"), "T", "");
        assertEquals(new BigDecimal("23500.00"), a.account("1000000001").balance());
        assertEquals(new BigDecimal("72500.00"), a.account("1000000003").balance());
        assertEquals(2, b.recentTransactions(10).size());
    }

    @Test
    void registrationOnOneInstanceCanLogInOnTheOther() {
        TestBank tb = TestBank.create();
        tb.bank.register("New Person", "new@example.com", "", "secret123", "SAVINGS", BigDecimal.TEN);
        assertEquals("new@example.com", tb.anotherInstance().bank.authenticate("new@example.com", "secret123").email());
    }

    @Test
    void concurrentTransfersOnTwoInstancesNeverOverspend() throws Exception {
        TestBank tb = TestBank.create();
        BankService[] instances = {tb.bank, tb.anotherInstance().bank};
        // Arjun (1000000007) has 5600.00; 20 transfers of 500 race from two instances: exactly 11 can succeed.
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            BankService s = instances[i % 2];
            String to = String.valueOf(1000000001L + (i % 5));
            results.add(pool.submit(() -> {
                try { s.transfer("1000000007", to, new BigDecimal("500"), "T", ""); return true; }
                catch (BankException e) { return false; }
            }));
        }
        int ok = 0;
        for (Future<Boolean> f : results) if (f.get()) ok++;
        pool.shutdown();
        assertEquals(11, ok);
        assertEquals(new BigDecimal("100.00"), tb.bank.account("1000000007").balance());
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
