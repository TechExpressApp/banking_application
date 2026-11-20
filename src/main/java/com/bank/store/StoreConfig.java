package com.bank.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
public class StoreConfig {

    private static final Logger log = LoggerFactory.getLogger(StoreConfig.class);

    @Bean
    public TableStore tableStore(@Value("${banking.storage}") String storage,
                                 @Value("${banking.csv.dir}") String csvDir,
                                 @Value("${banking.sheets.spreadsheet-id}") String sheetId,
                                 @Value("${banking.sheets.credentials-file}") String credentials,
                                 @Value("${banking.sheets.credentials-json:}") String credentialsJson) {
        TableStore store = switch (storage.toLowerCase()) {
            case "sheets" -> new GoogleSheetsTableStore(sheetId, credentials, credentialsJson);
            case "csv" -> new CsvTableStore(Path.of(csvDir));
            default -> throw new IllegalStateException("Unknown banking.storage '" + storage + "' (use csv or sheets)");
        };
        log.info("Bank data storage: {}", store.describe());
        return store;
    }
}
