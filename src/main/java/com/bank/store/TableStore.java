package com.bank.store;

import java.util.List;

/**
 * Minimal tabular storage. Each table is a named sheet/file whose first row is the header.
 * Used only by {@link CsvTableStore} to import the old CSV data into the database.
 */
public interface TableStore {

    /** Returns the data rows (header excluded). Empty list if the table does not exist yet. */
    List<List<String>> readAll(String table);

    /** Replaces the whole table with the header followed by the given rows. */
    void writeAll(String table, List<String> header, List<List<String>> rows);

    String describe();
}
