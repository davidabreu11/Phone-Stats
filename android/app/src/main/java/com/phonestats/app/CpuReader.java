package com.phonestats.app;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads per-core clock speeds from /sys. Also tries /proc/stat for true CPU usage;
 * Android normally blocks that for apps, in which case usage stays at -1.
 */
final class CpuReader {

    static final class Core {
        boolean online;
        long curKhz = -1;
        long maxKhz = -1;
        float usage = -1f;
    }

    static final class Snapshot {
        Core[] cores;
        int online;
        long avgCurKhz;
        float avgFraction;
        float usagePercent = -1f;
    }

    private final int count;
    private final long[] prevTotal;
    private final long[] prevIdle;
    private boolean statReadable = true;

    CpuReader() {
        count = detectCores();
        prevTotal = new long[count + 1];
        prevIdle = new long[count + 1];
    }

    int coreCount() {
        return count;
    }

    boolean usageAvailable() {
        return statReadable;
    }

    private static int detectCores() {
        int n = 0;
        try {
            File[] f = new File("/sys/devices/system/cpu").listFiles(
                    (dir, name) -> name.matches("cpu\\d+"));
            if (f != null) n = f.length;
        } catch (Exception ignored) {
        }
        return Math.max(n, Runtime.getRuntime().availableProcessors());
    }

    Snapshot read() {
        Snapshot s = new Snapshot();
        s.cores = new Core[count];
        long sumCur = 0;
        long sumMax = 0;
        for (int i = 0; i < count; i++) {
            Core c = new Core();
            String base = "/sys/devices/system/cpu/cpu" + i + "/cpufreq/";
            c.curKhz = readLong(base + "scaling_cur_freq");
            c.maxKhz = readLong(base + "cpuinfo_max_freq");
            c.online = c.curKhz > 0;
            if (c.online) {
                s.online++;
                sumCur += c.curKhz;
                sumMax += c.maxKhz > 0 ? c.maxKhz : c.curKhz;
            }
            s.cores[i] = c;
        }
        s.avgCurKhz = s.online > 0 ? sumCur / s.online : 0;
        s.avgFraction = sumMax > 0 ? (float) sumCur / sumMax : 0f;
        if (statReadable) readStat(s);
        return s;
    }

    private void readStat(Snapshot s) {
        List<String> lines;
        try {
            lines = readLines("/proc/stat");
        } catch (Exception e) {
            statReadable = false;
            return;
        }
        if (lines.isEmpty()) {
            statReadable = false;
            return;
        }
        for (String line : lines) {
            if (!line.startsWith("cpu")) break;
            String[] p = line.trim().split("\\s+");
            if (p.length < 5) continue;
            int idx;
            if (p[0].equals("cpu")) {
                idx = 0;
            } else {
                try {
                    idx = Integer.parseInt(p[0].substring(3)) + 1;
                } catch (NumberFormatException e) {
                    continue;
                }
            }
            if (idx > count) continue;
            long total = 0;
            for (int k = 1; k < p.length && k <= 8; k++) total += Long.parseLong(p[k]);
            long idle = Long.parseLong(p[4]) + (p.length > 5 ? Long.parseLong(p[5]) : 0);
            long dt = total - prevTotal[idx];
            long di = idle - prevIdle[idx];
            if (prevTotal[idx] != 0 && dt > 0) {
                float u = 100f * (dt - di) / dt;
                if (idx == 0) s.usagePercent = u;
                else s.cores[idx - 1].usage = u;
            }
            prevTotal[idx] = total;
            prevIdle[idx] = idle;
        }
    }

    private static long readLong(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String line = r.readLine();
            return line == null ? -1 : Long.parseLong(line.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private static List<String> readLines(String path) throws Exception {
        List<String> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = r.readLine()) != null) out.add(line);
        }
        return out;
    }
}
