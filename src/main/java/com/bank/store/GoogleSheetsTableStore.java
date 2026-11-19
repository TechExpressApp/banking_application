package com.bank.store;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.api.services.sheets.v4.model.*;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Stores each table as a tab (worksheet) in one Google Spreadsheet.
 * Authenticates with a Google Cloud service-account JSON key; the spreadsheet must be
 * shared (Editor) with the service account's e-mail address. Missing tabs are created automatically.
 */
public class GoogleSheetsTableStore implements TableStore {

    private final Sheets sheets;
    private final String spreadsheetId;
    private final Set<String> knownTabs = new HashSet<>();

    public GoogleSheetsTableStore(String spreadsheetId, String credentialsFile) {
        if (spreadsheetId == null || spreadsheetId.isBlank()) {
            throw new IllegalStateException("banking.sheets.spreadsheet-id (env BANKING_SHEET_ID) is required for sheets storage");
        }
        this.spreadsheetId = spreadsheetId;
        try (InputStream in = new FileInputStream(credentialsFile)) {
            GoogleCredentials creds = GoogleCredentials.fromStream(in).createScoped(List.of(SheetsScopes.SPREADSHEETS));
            this.sheets = new Sheets.Builder(GoogleNetHttpTransport.newTrustedTransport(),
                    GsonFactory.getDefaultInstance(), new HttpCredentialsAdapter(creds))
                    .setApplicationName("Banking Application")
                    .build();
            Spreadsheet ss = sheets.spreadsheets().get(spreadsheetId).execute();
            ss.getSheets().forEach(s -> knownTabs.add(s.getProperties().getTitle()));
        } catch (IOException | java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Could not connect to Google Sheet " + spreadsheetId + ": " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized List<List<String>> readAll(String table) {
        if (!knownTabs.contains(table)) return new ArrayList<>();
        try {
            ValueRange vr = sheets.spreadsheets().values().get(spreadsheetId, table + "!A:Z").execute();
            List<List<Object>> values = vr.getValues();
            List<List<String>> rows = new ArrayList<>();
            if (values == null) return rows;
            int width = values.isEmpty() ? 0 : values.get(0).size();
            for (int i = 1; i < values.size(); i++) {           // skip header
                List<Object> v = values.get(i);
                if (v.isEmpty()) continue;
                List<String> row = new ArrayList<>();
                for (int c = 0; c < Math.max(width, v.size()); c++) {
                    row.add(c < v.size() && v.get(c) != null ? v.get(c).toString() : "");
                }
                rows.add(row);
            }
            return rows;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized void writeAll(String table, List<String> header, List<List<String>> rows) {
        try {
            ensureTab(table);
            List<List<Object>> values = new ArrayList<>();
            values.add(new ArrayList<>(header));
            rows.forEach(r -> values.add(new ArrayList<>(r)));
            sheets.spreadsheets().values().clear(spreadsheetId, table + "!A:Z", new ClearValuesRequest()).execute();
            sheets.spreadsheets().values()
                    .update(spreadsheetId, table + "!A1", new ValueRange().setValues(values))
                    .setValueInputOption("RAW")       // keep account numbers / amounts as text, exactly as written
                    .execute();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void ensureTab(String table) throws IOException {
        if (knownTabs.contains(table)) return;
        var add = new Request().setAddSheet(new AddSheetRequest().setProperties(new SheetProperties().setTitle(table)));
        sheets.spreadsheets().batchUpdate(spreadsheetId,
                new BatchUpdateSpreadsheetRequest().setRequests(List.of(add))).execute();
        knownTabs.add(table);
    }

    @Override
    public String describe() {
        return "Google Sheet https://docs.google.com/spreadsheets/d/" + spreadsheetId;
    }
}
