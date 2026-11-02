package com.phonestats.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Survey readings, kept as a CSV file in the app's private storage. */
final class SurveyStore {

    static final String[] HEADER = {"time", "spot", "type", "network", "tech", "band", "channel",
            "frequency_mhz", "id", "signal_dbm", "rsrq_db", "sinr_db", "rating", "in_use", "scan_age_s"};
    static final int TIME = 0, SPOT = 1, TYPE = 2, NETWORK = 3, TECH = 4, BAND = 5, CHANNEL = 6,
            FREQ = 7, ID = 8, SIGNAL = 9, RSRQ = 10, SINR = 11, RATING = 12, IN_USE = 13, AGE = 14;

    private final File file;
    private final List<String[]> rows = new ArrayList<>();

    SurveyStore(Context c) {
        file = new File(c.getFilesDir(), "survey.csv");
        load();
    }

    List<String[]> rows() {
        return rows;
    }

    private void load() {
        rows.clear();
        if (!file.exists()) return;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line = r.readLine(); // header
            while ((line = r.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] f = parse(line);
                if (f.length < HEADER.length) {
                    String[] padded = new String[HEADER.length];
                    for (int i = 0; i < padded.length; i++) padded[i] = i < f.length ? f[i] : "";
                    f = padded;
                }
                rows.add(f);
            }
        } catch (IOException ignored) {
        }
    }

    void append(List<String[]> add) throws IOException {
        boolean fresh = !file.exists() || file.length() == 0;
        try (Writer w = new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8)) {
            if (fresh) w.write(line(HEADER));
            for (String[] r : add) w.write(line(r));
        }
        rows.addAll(add);
    }

    void deleteSpot(String spot) throws IOException {
        rows.removeIf(r -> r[SPOT].equals(spot));
        rewrite();
    }

    void clear() throws IOException {
        rows.clear();
        rewrite();
    }

    private void rewrite() throws IOException {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(file, false), StandardCharsets.UTF_8)) {
            w.write(line(HEADER));
            for (String[] r : rows) w.write(line(r));
        }
    }

    /** Copies the CSV into the phone's Downloads folder and returns its address for sharing. */
    Uri exportToDownloads(Context c, String name) throws IOException {
        return exportFile(c, file, name);
    }

    /** Copies any CSV file into the phone's Downloads folder. */
    static Uri exportFile(Context c, File file, String name) throws IOException {
        ContentResolver cr = c.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, "text/csv");
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IOException("Couldn't create the file in Downloads");
        try (OutputStream os = cr.openOutputStream(uri); InputStream in = new FileInputStream(file)) {
            if (os == null) throw new IOException("Couldn't open the file in Downloads");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
        return uri;
    }

    static String line(String[] fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) sb.append(',');
            String f = fields[i] == null ? "" : fields[i].replace('\n', ' ').replace('\r', ' ');
            if (f.indexOf(',') >= 0 || f.indexOf('"') >= 0) {
                sb.append('"').append(f.replace("\"", "\"\"")).append('"');
            } else {
                sb.append(f);
            }
        }
        return sb.append('\n').toString();
    }

    static String[] parse(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cur.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }
}
