package com.bank;

import com.bank.model.Transaction;
import com.bank.repo.BankRepository;
import com.bank.service.BankException;
import com.bank.service.BankService;
import com.bank.store.CsvTableStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class BankServiceTest {

    @TempDir Path dir;
    BankRepository repo;
    BankService bank;

    @BeforeEach
    void setUp() throws Exception {
        var encoder = new BCryptPasswordEncoder(4);
        repo = new BankRepository(new CsvTableStore(dir), encoder, "Password@123");
        Method load = BankRepository.class.getDeclaredMethod("load");
        load.setAccessible(true);
        load.invoke(repo);
        bank = new BankService(repo, encoder, event -> { });
    }

    private BigDecimal balance(String acc) {
        return repo.findAccount(acc).orElseThrow().balance();
    }

    @Test
    void seedsTenAccounts() {
        assertEquals(10, bank.allAccounts().size());
        assertEquals(new BigDecimal("25000.00"), balance("1000000001"));
    }

    @Test
    void transferMovesMoneyAndPersists() throws Exception {
        Transaction t = bank.transfer("1000000001", "1000000002", new BigDecimal("1000"), "TELLER", "rent");
        assertEquals(new BigDecimal("24000.00"), balance("1000000001"));
        assertEquals(new BigDecimal("19500.50"), balance("1000000002"));
        assertEquals(new BigDecimal("1000.00"), t.amount());

        // reload from disk: balances and transaction survive
        setUp();
        assertEquals(new BigDecimal("24000.00"), balance("1000000001"));
        assertEquals(1, bank.recentTransactions(10).size());
    }

    @Test
    void rejectsInvalidTransfers() {
        assertThrows(BankException.class, () -> bank.transfer("1000000001", "1000000001", BigDecimal.TEN, "T", ""));
        assertThrows(BankException.class, () -> bank.transfer("1000000001", "1000000002", BigDecimal.ZERO, "T", ""));
        assertThrows(BankException.class, () -> bank.transfer("1000000001", "1000000002", new BigDecimal("0.001"), "T", ""));
        assertThrows(BankException.class, () -> bank.transfer("1000000007", "1000000002", new BigDecimal("5600.01"), "T", ""));
        assertThrows(BankException.class, () -> bank.transfer("1000000001", "9999999999", BigDecimal.TEN, "T", ""));
        assertEquals(new BigDecimal("25000.00"), balance("1000000001"));
    }

    @Test
    void registerLoginBeneficiaryAndTransfer() {
        var reg = bank.register("Test User", "test@example.com", "", "secret123", "SAVINGS", new BigDecimal("500"));
        assertEquals("1000000011", reg.account().accountNumber());
        assertEquals(reg.customer().customerId(), bank.authenticate("TEST@example.com", "secret123").customerId());
        assertThrows(BankException.class, () -> bank.authenticate("test@example.com", "wrong"));
        assertThrows(BankException.class, () -> bank.register("X", "test@example.com", "", "secret123", "SAVINGS", null));

        String cid = reg.customer().customerId();
        assertThrows(BankException.class, () -> bank.addBeneficiary(cid, reg.account().accountNumber(), "me"));
        var ben = bank.addBeneficiary(cid, "1000000003", "Rahul");
        assertThrows(BankException.class, () -> bank.addBeneficiary(cid, "1000000003", "again"));

        bank.transferToBeneficiary(cid, reg.account().accountNumber(), ben.beneficiaryId(), new BigDecimal("200"), "");
        assertEquals(new BigDecimal("300.00"), balance(reg.account().accountNumber()));
        assertEquals(new BigDecimal("72200.00"), balance("1000000003"));

        // cannot spend from somebody else's account, or use somebody else's beneficiary
        assertThrows(BankException.class, () ->
                bank.transferToBeneficiary(cid, "1000000001", ben.beneficiaryId(), BigDecimal.ONE, ""));
        assertThrows(BankException.class, () ->
                bank.transferToBeneficiary("CUST001", "1000000001", ben.beneficiaryId(), BigDecimal.ONE, ""));
    }

    @Test
    void concurrentTransfersKeepTotalConstant() throws Exception {
        BigDecimal before = bank.allAccounts().stream().map(a -> a.balance()).reduce(BigDecimal.ZERO, BigDecimal::add);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            String from = String.valueOf(1000000001L + (i % 10));
            String to = String.valueOf(1000000001L + ((i + 3) % 10));
            fs.add(pool.submit(() -> { try { bank.transfer(from, to, new BigDecimal("37.25"), "T", ""); } catch (BankException ignored) { } }));
        }
        for (Future<?> f : fs) f.get();
        pool.shutdown();
        BigDecimal after = bank.allAccounts().stream().map(a -> a.balance()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(before, after);
    }
}
