import SwiftUI
import UIKit

// MARK: - Colors (match the Android app)

extension Color {
    static let pageBg = Color(red: 0.059, green: 0.067, blue: 0.082)
    static let cardBg = Color(red: 0.102, green: 0.114, blue: 0.137)
    static let trackBg = Color(red: 0.176, green: 0.192, blue: 0.224)
    static let good = Color(red: 0.506, green: 0.788, blue: 0.584)
    static let warn = Color(red: 0.992, green: 0.839, blue: 0.388)
    static let bad = Color(red: 0.949, green: 0.545, blue: 0.510)
    static let info = Color(red: 0.541, green: 0.706, blue: 0.973)

    static func level(_ f: Double) -> Color { f < 0.6 ? .good : (f < 0.85 ? .warn : .bad) }
}

// MARK: - Tabs

struct ContentView: View {
    var body: some View {
        TabView {
            StatsView()
                .tabItem { Label("Stats", systemImage: "chart.bar.fill") }
            CommuteLogView()
                .tabItem { Label("Commute Log", systemImage: "tram.fill") }
        }
        .preferredColorScheme(.dark)
    }
}

// MARK: - Stats screen

struct StatsView: View {
    @StateObject private var model = StatsModel()
    @Environment(\.scenePhase) private var phase
    private let timer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Phone Stats").font(.largeTitle.bold())
                    Text("Updates every second while open").font(.footnote).foregroundStyle(.secondary)
                }
                .padding(.bottom, 4)

                memoryCard
                cpuCard
                batteryCard
                heatCard
                storageCard
                networkCard
                systemCard

                Text("iOS doesn't let apps read cell signal strength, bands, towers, nearby Wi-Fi networks, or battery temperature and health, so those aren't shown here.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 4)
            }
            .padding(16)
        }
        .background(Color.pageBg.ignoresSafeArea())
        .preferredColorScheme(.dark)
        .onReceive(timer) { _ in
            if phase == .active { model.refresh() }
        }
    }

    // MARK: Cards

    private var memoryCard: some View {
        Card("MEMORY (RAM)") {
            let m = model.memory
            if let used = m.used, m.total > 0 {
                let f = Double(used) / Double(m.total)
                Text("\(gb(used)) of \(gb(m.total)) used").font(.title2.bold())
                Bar(fraction: f, color: .level(f))
                Text("\(pct(f)) used · \(gb(m.total - used)) free").font(.footnote).foregroundStyle(.secondary)
            } else {
                Text("\(gb(m.total)) installed").font(.title2.bold())
                Text("iOS isn't sharing system-wide memory use with apps.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            if let app = m.appFootprint {
                Text("This app: \(mb(app))" + (m.appAvailable.map { " · it could still use \(gb($0))" } ?? ""))
                    .font(.footnote).foregroundStyle(.secondary)
            }
        }
    }

    private var cpuCard: some View {
        Card("PROCESSOR") {
            if let total = model.cpuTotal {
                Text("\(pct(total / 100)) busy").font(.title2.bold())
                Bar(fraction: total / 100, color: .level(total / 100))
                ForEach(Array(model.cpuCores.enumerated()), id: \.offset) { item in
                    HStack {
                        Text("Core \(item.offset)")
                        Spacer()
                        Text(pct(item.element / 100))
                    }
                    .font(.footnote.monospaced())
                    .padding(.top, 4)
                    Bar(fraction: item.element / 100, color: .level(item.element / 100))
                }
            } else if model.cpuUnavailable {
                Text("Not available").font(.title2.bold())
                Text("This iOS version isn't sharing overall CPU use with apps.")
                    .font(.footnote).foregroundStyle(.secondary)
            } else {
                Text("Measuring…").font(.title2.bold())
            }
            Text(coreSummary).font(.footnote).foregroundStyle(.secondary).padding(.top, 2)
        }
    }

    private var batteryCard: some View {
        Card("BATTERY") {
            if model.batteryLevel >= 0 {
                let f = Double(model.batteryLevel)
                Text("\(pct(f)) · \(batteryText)").font(.title2.bold())
                Bar(fraction: f, color: f <= 0.15 ? .bad : (f <= 0.3 ? .warn : .good))
            } else {
                Text("Not available").font(.title2.bold())
            }
            Text("Low Power Mode: \(model.lowPower ? "on" : "off") · iOS reports the level in 5% steps")
                .font(.footnote).foregroundStyle(.secondary)
        }
    }

    private var heatCard: some View {
        Card("HEAT & THROTTLING") {
            let h = heat
            Text(h.text).font(.title3.bold()).foregroundStyle(h.color)
            Bar(fraction: h.fraction, color: h.color)
            Text("Apple's own heat level. At \"Serious\" and above, the iPhone slows itself down to cool off.")
                .font(.footnote).foregroundStyle(.secondary)
        }
    }

    private var storageCard: some View {
        Card("STORAGE") {
            if model.storageTotal > 0 {
                let used = model.storageTotal - model.storageFree
                let f = Double(used) / Double(model.storageTotal)
                Text("\(gb(UInt64(max(used, 0)))) of \(gb(UInt64(model.storageTotal))) used").font(.title2.bold())
                Bar(fraction: f, color: .level(f))
                Text("\(pct(f)) used · \(gb(UInt64(max(model.storageFree, 0)))) free")
                    .font(.footnote).foregroundStyle(.secondary)
            } else {
                Text("Reading…").font(.title2.bold())
            }
        }
    }

    private var networkCard: some View {
        Card("NETWORK") {
            Text(model.connection).font(.title3.bold())
            if model.radios.isEmpty {
                Text("Cellular: no active SIM or no service").font(.footnote).foregroundStyle(.secondary)
            } else {
                ForEach(model.radios, id: \.self) { line in
                    Text(line).font(.subheadline)
                }
            }
            Text("iOS only tells apps the network type, not signal strength or bands.")
                .font(.footnote).foregroundStyle(.secondary)
        }
    }

    private var systemCard: some View {
        Card("SYSTEM") {
            Group {
                Text("Model: \(model.modelId)")
                Text("iOS \(model.iosVersion)")
                Text(coreSummary)
                Text("Up since restart: \(duration(model.uptime))")
            }
            .font(.subheadline)
        }
    }

    // MARK: Text helpers

    private var coreSummary: String {
        if let p = model.perfCores, let e = model.effCores {
            return "\(model.coreCount) cores: \(p) performance + \(e) efficiency"
        }
        return "\(model.coreCount) cores"
    }

    private var batteryText: String {
        switch model.batteryState {
        case .charging: return "Charging"
        case .full: return "Full"
        case .unplugged: return "On battery"
        default: return "Unknown"
        }
    }

    private var heat: (text: String, color: Color, fraction: Double) {
        switch model.thermal {
        case .nominal: return ("Normal", .good, 0.15)
        case .fair: return ("Warm (fair)", .warn, 0.45)
        case .serious: return ("Hot (serious): slowing down", .bad, 0.75)
        case .critical: return ("Very hot (critical)", .bad, 1.0)
        @unknown default: return ("Unknown", .secondary, 0)
        }
    }
}

// MARK: - Building blocks

struct Card<Content: View>: View {
    let title: String
    let content: Content

    init(_ title: String, @ViewBuilder content: () -> Content) {
        self.title = title
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.caption.bold()).foregroundStyle(.secondary).tracking(1)
            content
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Color.cardBg))
    }
}

struct Bar: View {
    let fraction: Double
    let color: Color

    var body: some View {
        GeometryReader { g in
            let f = min(max(fraction.isNaN ? 0 : fraction, 0), 1)
            ZStack(alignment: .leading) {
                Capsule().fill(Color.trackBg)
                Capsule().fill(color)
                    .frame(width: max(8, g.size.width * f))
                    .opacity(f > 0 ? 1 : 0)
            }
        }
        .frame(height: 8)
        .padding(.vertical, 4)
    }
}

// MARK: - Formatting

private func gb(_ bytes: UInt64) -> String {
    String(format: "%.1f GB", Double(bytes) / 1_073_741_824)
}

private func mb(_ bytes: UInt64) -> String {
    String(format: "%.0f MB", Double(bytes) / 1_048_576)
}

private func pct(_ f: Double) -> String {
    String(format: "%.0f%%", f * 100)
}

private func duration(_ seconds: TimeInterval) -> String {
    let m = Int(seconds) / 60
    let d = m / 1440
    let h = (m % 1440) / 60
    let mm = m % 60
    if d > 0 { return "\(d)d \(h)h \(mm)m" }
    if h > 0 { return "\(h)h \(mm)m" }
    return "\(mm)m"
}

#Preview {
    ContentView()
}
