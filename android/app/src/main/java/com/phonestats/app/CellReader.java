package com.phonestats.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.telephony.CellIdentity;
import android.telephony.CellIdentityGsm;
import android.telephony.CellIdentityLte;
import android.telephony.CellIdentityNr;
import android.telephony.CellIdentityWcdma;
import android.telephony.CellInfo;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoNr;
import android.telephony.CellInfoWcdma;
import android.telephony.CellSignalStrength;
import android.telephony.CellSignalStrengthLte;
import android.telephony.CellSignalStrengthNr;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads cell towers for every active SIM. Requires precise Location permission.
 * Phones normally only measure towers belonging to the carriers they have a SIM for.
 */
final class CellReader {

    static final int NA = Integer.MAX_VALUE; // same as CellInfo.UNAVAILABLE

    static final class Cell {
        String carrier = "Not identified";
        String tech = "";
        String band = "";
        String channel = "";
        String id = "";
        String bandwidth = "";
        boolean registered;
        int signal = NA;  // RSRP / SS-RSRP / RSSI in dBm
        int quality = NA; // RSRQ in dB
        int sinr = NA;    // 5G SINR in dB
    }

    static final class Sim {
        int subId;
        int slot;
        String name;
        String error;
        long updatedMs;
        final List<Cell> cells = new ArrayList<>();
    }

    private final Context ctx;
    private final Runnable onUpdate;
    private final Map<Integer, Sim> sims = new LinkedHashMap<>();

    CellReader(Context ctx, Runnable onUpdate) {
        this.ctx = ctx;
        this.onUpdate = onUpdate;
    }

    List<Sim> sims() {
        return new ArrayList<>(sims.values());
    }

    @SuppressLint("MissingPermission")
    void requestUpdate() {
        TelephonyManager base = ctx.getSystemService(TelephonyManager.class);
        if (base == null) return;
        List<SubscriptionInfo> subs = null;
        try {
            SubscriptionManager sm = ctx.getSystemService(SubscriptionManager.class);
            if (sm != null) subs = sm.getActiveSubscriptionInfoList();
        } catch (SecurityException ignored) {
            // Phone permission denied: fall back to the default SIM below.
        }
        Set<Integer> live = new HashSet<>();
        if (subs == null || subs.isEmpty()) {
            live.add(-1);
            request(base, -1, -1, nonEmpty(base.getNetworkOperatorName(), "Your carrier"));
        } else {
            for (SubscriptionInfo si : subs) {
                int id = si.getSubscriptionId();
                live.add(id);
                CharSequence n = si.getCarrierName();
                if (n == null || n.length() == 0) n = si.getDisplayName();
                request(base.createForSubscriptionId(id), id, si.getSimSlotIndex(),
                        nonEmpty(n == null ? null : n.toString(), "SIM"));
            }
        }
        sims.keySet().retainAll(live);
    }

    @SuppressLint("MissingPermission")
    private void request(TelephonyManager tm, int subId, int slot, String name) {
        try {
            tm.requestCellInfoUpdate(ctx.getMainExecutor(), new TelephonyManager.CellInfoCallback() {
                @Override
                public void onCellInfo(List<CellInfo> list) {
                    store(tm, subId, slot, name, list, null);
                }

                @Override
                public void onError(int errorCode, Throwable detail) {
                    store(tm, subId, slot, name, cached(tm), null);
                }
            });
        } catch (SecurityException e) {
            store(tm, subId, slot, name, null, "Needs precise Location permission");
        } catch (Exception e) {
            store(tm, subId, slot, name, null, "Couldn't read towers: " + e.getMessage());
        }
    }

    @SuppressLint("MissingPermission")
    private static List<CellInfo> cached(TelephonyManager tm) {
        try {
            return tm.getAllCellInfo();
        } catch (Exception e) {
            return null;
        }
    }

    private void store(TelephonyManager tm, int subId, int slot, String name, List<CellInfo> list, String error) {
        Sim s = new Sim();
        s.subId = subId;
        s.slot = slot;
        s.name = name;
        s.error = error;
        s.updatedMs = SystemClock.elapsedRealtime();
        boolean nrRegistered = false;
        if (list != null) {
            for (CellInfo ci : list) {
                Cell c = parse(ci, name);
                if (c == null) continue;
                s.cells.add(c);
                if (c.registered && c.tech.equals("5G")) nrRegistered = true;
            }
        }
        if (error == null && !nrRegistered) addNsa(tm, s, name);
        s.cells.sort((x, y) -> {
            if (x.registered != y.registered) return x.registered ? -1 : 1;
            return Integer.compare(sortable(y.signal), sortable(x.signal));
        });
        sims.put(subId, s);
        onUpdate.run();
    }

    /** 5G "non-standalone": a 5G signal paired with an LTE tower. It shows up only in signal strength. */
    private static void addNsa(TelephonyManager tm, Sim s, String name) {
        SignalStrength ss;
        try {
            ss = tm.getSignalStrength();
        } catch (Exception e) {
            return;
        }
        if (ss == null) return;
        for (CellSignalStrength css : ss.getCellSignalStrengths()) {
            if (!(css instanceof CellSignalStrengthNr)) continue;
            CellSignalStrengthNr nr = (CellSignalStrengthNr) css;
            int rsrp = nr.getSsRsrp() != NA ? nr.getSsRsrp() : nr.getCsiRsrp();
            if (rsrp == NA) continue;
            Cell c = new Cell();
            c.carrier = name;
            c.tech = "5G (NSA)";
            c.registered = true;
            c.signal = rsrp;
            c.quality = nr.getSsRsrq() != NA ? nr.getSsRsrq() : nr.getCsiRsrq();
            c.sinr = nr.getSsSinr() != NA ? nr.getSsSinr() : nr.getCsiSinr();
            c.id = "5G paired with the LTE tower (band not reported)";
            s.cells.add(c);
        }
    }

    private static Cell parse(CellInfo ci, String simName) {
        Cell c = new Cell();
        c.registered = ci.isRegistered();
        String op;
        if (ci instanceof CellInfoNr) {
            CellIdentityNr id = (CellIdentityNr) ((CellInfoNr) ci).getCellIdentity();
            CellSignalStrengthNr s = (CellSignalStrengthNr) ((CellInfoNr) ci).getCellSignalStrength();
            c.tech = "5G";
            op = operator(id, id.getMccString(), id.getMncString());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) c.band = bands("n", id.getBands());
            if (id.getNrarfcn() != NA) c.channel = "NR-ARFCN " + id.getNrarfcn();
            if (id.getPci() != NA) c.id = "PCI " + id.getPci();
            c.signal = s.getSsRsrp();
            c.quality = s.getSsRsrq();
            c.sinr = s.getSsSinr();
        } else if (ci instanceof CellInfoLte) {
            CellIdentityLte id = ((CellInfoLte) ci).getCellIdentity();
            CellSignalStrengthLte s = ((CellInfoLte) ci).getCellSignalStrength();
            c.tech = "LTE";
            op = operator(id, id.getMccString(), id.getMncString());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) c.band = bands("B", id.getBands());
            if (id.getEarfcn() != NA) c.channel = "EARFCN " + id.getEarfcn();
            if (id.getPci() != NA) c.id = "PCI " + id.getPci();
            int bw = id.getBandwidth();
            if (bw != NA && bw > 0) c.bandwidth = (bw / 1000) + " MHz";
            c.signal = s.getRsrp();
            c.quality = s.getRsrq();
        } else if (ci instanceof CellInfoWcdma) {
            CellIdentityWcdma id = ((CellInfoWcdma) ci).getCellIdentity();
            c.tech = "3G";
            op = operator(id, id.getMccString(), id.getMncString());
            if (id.getUarfcn() != NA) c.channel = "UARFCN " + id.getUarfcn();
            if (id.getPsc() != NA) c.id = "PSC " + id.getPsc();
            c.signal = ((CellInfoWcdma) ci).getCellSignalStrength().getDbm();
        } else if (ci instanceof CellInfoGsm) {
            CellIdentityGsm id = ((CellInfoGsm) ci).getCellIdentity();
            c.tech = "2G";
            op = operator(id, id.getMccString(), id.getMncString());
            if (id.getArfcn() != NA) c.channel = "ARFCN " + id.getArfcn();
            c.signal = ((CellInfoGsm) ci).getCellSignalStrength().getDbm();
        } else {
            return null;
        }
        if (op != null) c.carrier = op;
        else if (c.registered) c.carrier = simName;
        if (c.signal != NA && (c.signal > -20 || c.signal < -160)) c.signal = NA; // ignore junk values
        return c;
    }

    private static String operator(CellIdentity id, String mcc, String mnc) {
        CharSequence a = id.getOperatorAlphaShort();
        if (a == null || a.length() == 0) a = id.getOperatorAlphaLong();
        if (a != null && a.length() > 0) return a.toString();
        if (mcc != null && mnc != null) return "Network " + mcc + "-" + mnc;
        return null;
    }

    private static String bands(String prefix, int[] b) {
        if (b == null || b.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length; i++) {
            if (i > 0) sb.append('/');
            sb.append(prefix).append(b[i]);
        }
        return sb.toString();
    }

    private static int sortable(int signal) {
        return signal == NA ? -999 : signal;
    }

    private static String nonEmpty(String s, String fallback) {
        return s == null || s.trim().isEmpty() ? fallback : s.trim();
    }
}
