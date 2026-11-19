package com.bank.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** Stores each table as {@code <dir>/<table>.csv}. Handles quoted fields containing commas/quotes. */
public class CsvTableStore implements TableStore {

    private final Path dir;

    public CsvTableStore(Path dir) {
        this.dir = dir;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public List<List<String>> readAll(String table) {
        Path file = dir.resolve(table + ".csv");
        if (!Files.exists(file)) return new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            List<List<String>> rows = new ArrayList<>();
            for (int i = 1; i < lines.size(); i++) {           // skip header
                if (!lines.get(i).isBlank()) rows.add(parseLine(lines.get(i)));
            }
            return rows;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void writeAll(String table, List<String> header, List<List<String>> rows) {
        Path file = dir.resolve(table + ".csv");
        Path tmp = dir.resolve(table + ".csv.tmp");
        List<String> lines = new ArrayList<>();
        lines.add(toLine(header));
        rows.forEach(r -> lines.add(toLine(r)));
        try {
            Files.write(tmp, lines, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String describe() {
        return "CSV files in " + dir.toAbsolutePath();
    }

    private static String toLine(List<String> fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) sb.append(',');
            String f = fields.get(i) == null ? "" : fields.get(i);
            if (f.contains(",") || f.contains("\"") || f.contains("\n")) {
                sb.append('"').append(f.replace("\"", "\"\"")).append('"');
            } else {
                sb.append(f);
            }
        }
        return sb.toString();
    }

    static List<String> parseLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                else if (c == '"') quoted = false;
                else cur.append(c);
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }
}
