import SwiftUI
import UIKit
import Network
import CoreTelephony

/// Every 2 seconds while running, checks whether data actually works and records which line carries it.
/// Runs only between Start and Stop, and only while the app is on screen (iOS pauses it otherwise).
@MainActor
final class CommuteLogger: ObservableObject {
    @Published private(set) var running = false
    @Published private(set) var started: Date?
    @Published private(set) var checks = 0
    @Published private(set) var okChecks = 0
    @Published private(set) var outages = 0
    @Published private(set) var longestOutage: TimeInterval = 0
    @Published private(set) var outageSince: Date?
    @Published private(set) var lastOk: Bool?
    @Published private(set) var lastLatencyMs: Int?
    @Published private(set) var network = "unknown"
    @Published private(set) var lineSummary = ""
    @Published private(set) var recoveries: [TimeInterval] = []
    @Published private(set) var fileURL: URL?
    @Published private(set) var stopReason = ""

    static let autoStopKey = "logAutoStop"
    static let autoStopAfter: TimeInterval = 30 * 60
    private var autoStop: Bool { UserDefaults.standard.object(forKey: Self.autoStopKey) as? Bool ?? true }

    private var task: Task<Void, Never>?
    private let monitor = NWPathMonitor()
    private let telephony = CTTelephonyNetworkInfo()
    private let session: URLSession
    private var serviceUp = true
    private var serviceBack: Date?

    // A tiny Google page that exists only to answer "is the internet working?" (HTTP 204, no content)
    private let probeURL = URL(string: "https://www.gstatic.com/generate_204")!

    init() {
        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = 3
        cfg.timeoutIntervalForResource = 3
        cfg.requestCachePolicy = .reloadIgnoringLocalCacheData
        cfg.waitsForConnectivity = false
        session = URLSession(configuration: cfg)
        monitor.pathUpdateHandler = { [weak self] path in
            let kind = CommuteLogger.kind(path)
            Task { @MainActor in self?.network = kind }
        }
        monitor.start(queue: DispatchQueue(label: "phonestats.logger.network"))
    }

    func start() {
        guard !running else { return }
        let df = DateFormatter()
        df.locale = Locale(identifier: "en_US_POSIX")
        df.dateFormat = "yyyy-MM-dd-HHmm"
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let url = docs.appendingPathComponent("commute-iphone-\(df.string(from: Date())).csv")
        let header = "time,elapsed_s,network,data_ok,latency_ms,error,data_line,line1,line2,note\n"
        do {
            try header.write(to: url, atomically: true, encoding: .utf8)
        } catch {
            return
        }
        fileURL = url
        started = Date()
        checks = 0
        okChecks = 0
        outages = 0
        longestOutage = 0
        outageSince = nil
        lastOk = nil
        lastLatencyMs = nil
        recoveries = []
        serviceUp = true
        serviceBack = nil
        stopReason = ""
        running = true
        UIApplication.shared.isIdleTimerDisabled = true // keep the screen on while logging

        task = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                await self.sample()
                try? await Task.sleep(nanoseconds: 2_000_000_000)
            }
        }
    }

    func stop(_ reason: String = "Stopped by you") {
        guard running else { return }
        stopReason = reason
        append([stamp(Date()), elapsed(Date()), network, "", "", "", "", "", "", reason])
        task?.cancel()
        task = nil
        if let since = outageSince {
            longestOutage = max(longestOutage, Date().timeIntervalSince(since))
        }
        running = false
        UIApplication.shared.isIdleTimerDisabled = false
    }

    /// Adds a note row, e.g. when the app leaves or returns to the screen.
    func note(_ text: String) {
        guard running else { return }
        append([stamp(Date()), elapsed(Date()), network, "", "", "", "", "", "", text])
    }

    private func sample() async {
        if autoStop, let s = started, Date().timeIntervalSince(s) >= Self.autoStopAfter {
            stop("Stopped automatically after 30 minutes")
            return
        }
        let r = radios()
        let up = !r.dataTech.isEmpty
        if up && !serviceUp && lastOk != true { serviceBack = Date() }
        if !up { serviceBack = nil }
        serviceUp = up

        var req = URLRequest(url: probeURL)
        req.timeoutInterval = 3
        req.cachePolicy = .reloadIgnoringLocalCacheData
        let t0 = Date()
        var ok = false
        var err = ""
        do {
            let (_, resp) = try await session.data(for: req)
            let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
            ok = code == 204 || code == 200
            if !ok { err = "HTTP \(code)" }
        } catch let e as URLError {
            err = e.code == .timedOut ? "timeout" : "URLError \(e.code.rawValue)"
        } catch {
            err = "error"
        }
        guard running else { return }

        let now = Date()
        let ms = Int(now.timeIntervalSince(t0) * 1000)
        checks += 1
        if ok { okChecks += 1 }
        if !ok && lastOk != false {
            outages += 1
            outageSince = now
        }
        if ok && lastOk == false, let since = outageSince {
            longestOutage = max(longestOutage, now.timeIntervalSince(since))
            outageSince = nil
        }
        if ok, let back = serviceBack {
            recoveries.append(now.timeIntervalSince(back))
            serviceBack = nil
        }
        lastOk = ok
        lastLatencyMs = ok ? ms : nil
        append([stamp(now), elapsed(now), network, ok ? "1" : "0", ok ? String(ms) : "", err,
                r.dataLine, r.line1, r.line2, ""])
    }

    private struct Radios {
        var line1 = ""
        var line2 = ""
        var dataLine = ""
        var dataTech = ""
    }

    private func radios() -> Radios {
        var r = Radios()
        let tech = telephony.serviceCurrentRadioAccessTechnology ?? [:]
        let keys = tech.keys.sorted()
        if keys.count > 0 { r.line1 = StatsModel.radioName(tech[keys[0]] ?? "") }
        if keys.count > 1 { r.line2 = StatsModel.radioName(tech[keys[1]] ?? "") }
        if let id = telephony.dataServiceIdentifier, let i = keys.firstIndex(of: id) {
            r.dataLine = "Line \(i + 1)"
            r.dataTech = tech[id] ?? ""
        } else if keys.count == 1 {
            r.dataLine = "Line 1"
            r.dataTech = tech[keys[0]] ?? ""
        }
        var parts: [String] = []
        if !r.line1.isEmpty { parts.append("Line 1: \(r.line1)") }
        if !r.line2.isEmpty { parts.append("Line 2: \(r.line2)") }
        if !r.dataLine.isEmpty { parts.append("data on \(r.dataLine)") }
        lineSummary = parts.isEmpty ? "No cellular service reported" : parts.joined(separator: " · ")
        return r
    }

    private func append(_ fields: [String]) {
        guard let url = fileURL else { return }
        let line = fields.map { $0.contains(",") ? "\"\($0)\"" : $0 }.joined(separator: ",") + "\n"
        guard let data = line.data(using: .utf8), let h = try? FileHandle(forWritingTo: url) else { return }
        defer { try? h.close() }
        _ = try? h.seekToEnd()
        try? h.write(contentsOf: data)
    }

    private func stamp(_ d: Date) -> String {
        let df = DateFormatter()
        df.locale = Locale(identifier: "en_US_POSIX")
        df.dateFormat = "yyyy-MM-dd HH:mm:ss"
        return df.string(from: d)
    }

    private func elapsed(_ d: Date) -> String {
        String(format: "%.1f", d.timeIntervalSince(started ?? d))
    }

    nonisolated static func kind(_ path: NWPath) -> String {
        guard path.status == .satisfied else { return "none" }
        if path.usesInterfaceType(.wifi) { return "wifi" }
        if path.usesInterfaceType(.cellular) { return "cellular" }
        return "other"
    }
}

struct CommuteLogView: View {
    @StateObject private var log = CommuteLogger()
    @Environment(\.scenePhase) private var phase
    @State private var now = Date()
    @AppStorage(CommuteLogger.autoStopKey) private var autoStop = true
    private let clock = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Commute Log").font(.largeTitle.bold())
                    Text("Tap Start when you board. Every 2 seconds it checks whether data actually works. The screen stays on while logging.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                .padding(.bottom, 4)

                Card("RIGHT NOW") {
                    Text(statusText).font(.title2.bold()).foregroundStyle(statusColor)
                    Bar(fraction: log.running ? 1 : 0, color: statusColor)
                    Text("Via \(log.network) · \(log.lineSummary)").font(.footnote).foregroundStyle(.secondary)
                }

                Card("THIS RIDE") {
                    ForEach(statsLines, id: \.self) { Text($0).font(.subheadline) }
                }

                Card("SETTINGS") {
                    Toggle("Stop automatically after 30 minutes", isOn: $autoStop)
                        .tint(Color.info)
                    Text("In case you forget. iPhone apps can't log in the background, so logging also pauses whenever you leave this screen or lock the phone.")
                        .font(.footnote).foregroundStyle(.secondary)
                }

                Button {
                    if log.running { log.stop() } else { log.start() }
                } label: {
                    Text(log.running ? "Stop logging" : "Start logging")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                        .background(Capsule().fill(log.running ? Color.trackBg : Color.info))
                        .foregroundStyle(log.running ? Color.white : Color.pageBg)
                }

                if let url = log.fileURL, !log.running {
                    ShareLink(item: url) {
                        Label("Export CSV", systemImage: "square.and.arrow.up")
                            .font(.headline)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 12)
                            .background(Capsule().fill(Color.trackBg))
                            .foregroundStyle(Color.white)
                    }
                }

                Text("iOS pauses logging when you leave the app or lock the phone; the file notes each pause. iOS doesn't share signal strength, so \"signal back → data back\" relies on when iOS reports the network type returning, which it may not always do.")
                    .font(.caption).foregroundStyle(.secondary).padding(.horizontal, 4)
            }
            .padding(16)
        }
        .background(Color.pageBg.ignoresSafeArea())
        .onReceive(clock) { now = $0 }
        .onChange(of: phase) { _, p in
            log.note(p == .active ? "resumed (app back on screen)" : "paused (app not on screen)")
        }
    }

    private var statusText: String {
        guard log.running || log.checks > 0 else { return "Not running" }
        if !log.running { return log.stopReason.isEmpty ? "Stopped" : log.stopReason }
        switch log.lastOk {
        case .some(true): return "Data working · \(log.lastLatencyMs ?? 0) ms"
        case .some(false):
            let secs = log.outageSince.map { Int(now.timeIntervalSince($0)) } ?? 0
            return "No data · \(secs) s"
        case .none: return "Checking…"
        }
    }

    private var statusColor: Color {
        guard log.running else { return .secondary }
        switch log.lastOk {
        case .some(true): return .good
        case .some(false): return .bad
        case .none: return .secondary
        }
    }

    private var statsLines: [String] {
        guard log.checks > 0, let started = log.started else { return ["Start logging to see results."] }
        var lines: [String] = []
        lines.append("Logging for \(logDuration(now.timeIntervalSince(started))) · \(log.checks) checks")
        lines.append(String(format: "Data worked %.0f%% of the time", 100.0 * Double(log.okChecks) / Double(log.checks)))
        var longest = log.longestOutage
        if log.running, let since = log.outageSince { longest = max(longest, now.timeIntervalSince(since)) }
        lines.append("Outages: \(log.outages) · longest \(logDuration(longest))")
        if log.recoveries.isEmpty {
            lines.append("Signal back → data back: no stations measured yet")
        } else {
            let avg = log.recoveries.reduce(0, +) / Double(log.recoveries.count)
            let worst = log.recoveries.max() ?? 0
            lines.append(String(format: "Signal back → data back: avg %.1f s, worst %.1f s (%d times)",
                                avg, worst, log.recoveries.count))
        }
        if log.running && autoStop {
            lines.append("Stops automatically in \(logDuration(max(0, CommuteLogger.autoStopAfter - now.timeIntervalSince(started))))")
        }
        return lines
    }
}

private func logDuration(_ seconds: TimeInterval) -> String {
    let s = Int(seconds)
    if s < 60 { return "\(s)s" }
    if s < 3600 { return "\(s / 60)m \(s % 60)s" }
    return "\(s / 3600)h \((s % 3600) / 60)m"
}
