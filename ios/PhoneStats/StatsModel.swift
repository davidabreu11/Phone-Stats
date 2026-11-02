import SwiftUI
import UIKit
import Network
import CoreTelephony

@MainActor
final class StatsModel: ObservableObject {
    @Published private(set) var memory = MemoryInfo(total: 0, used: nil, appFootprint: nil, appAvailable: nil)
    @Published private(set) var cpuTotal: Double?
    @Published private(set) var cpuCores: [Double] = []
    @Published private(set) var cpuUnavailable = false
    @Published private(set) var batteryLevel: Float = -1
    @Published private(set) var batteryState: UIDevice.BatteryState = .unknown
    @Published private(set) var lowPower = false
    @Published private(set) var thermal: ProcessInfo.ThermalState = .nominal
    @Published private(set) var storageTotal: Int64 = 0
    @Published private(set) var storageFree: Int64 = 0
    @Published private(set) var uptime: TimeInterval = 0
    @Published private(set) var connection = "Checking…"
    @Published private(set) var radios: [String] = []

    let modelId: String
    let iosVersion: String
    let coreCount: Int
    let perfCores: Int?
    let effCores: Int?

    private let cpu = CpuSampler()
    private var cpuMisses = 0
    private let monitor = NWPathMonitor()
    private let telephony = CTTelephonyNetworkInfo()

    init() {
        modelId = SystemInfo.modelIdentifier()
        iosVersion = UIDevice.current.systemVersion
        coreCount = ProcessInfo.processInfo.processorCount
        perfCores = SystemInfo.sysctlInt("hw.perflevel0.physicalcpu")
        effCores = SystemInfo.sysctlInt("hw.perflevel1.physicalcpu")
        UIDevice.current.isBatteryMonitoringEnabled = true

        monitor.pathUpdateHandler = { [weak self] path in
            let text = StatsModel.describe(path)
            Task { @MainActor in self?.connection = text }
        }
        monitor.start(queue: DispatchQueue(label: "phonestats.network"))
        refresh()
    }

    func refresh() {
        memory = SystemInfo.memory()

        if let s = cpu.sample() {
            cpuTotal = s.total
            cpuCores = s.cores
            cpuMisses = 0
            cpuUnavailable = false
        } else {
            cpuMisses += 1
            if !cpu.available || cpuMisses > 3 {
                cpuUnavailable = true
                cpuTotal = nil
                cpuCores = []
            }
        }

        let d = UIDevice.current
        batteryLevel = d.batteryLevel
        batteryState = d.batteryState
        lowPower = ProcessInfo.processInfo.isLowPowerModeEnabled
        thermal = ProcessInfo.processInfo.thermalState
        if let s = SystemInfo.storage() {
            storageTotal = s.total
            storageFree = s.free
        }
        uptime = ProcessInfo.processInfo.systemUptime
        radios = radioLines()
    }

    private func radioLines() -> [String] {
        guard let tech = telephony.serviceCurrentRadioAccessTechnology, !tech.isEmpty else { return [] }
        let dataId = telephony.dataServiceIdentifier
        let keys = tech.keys.sorted()
        var lines: [String] = []
        for (i, key) in keys.enumerated() {
            let name = keys.count > 1 ? "Line \(i + 1)" : "Cellular"
            let data = (keys.count > 1 && key == dataId) ? " · used for data" : ""
            lines.append("\(name): \(StatsModel.radioName(tech[key] ?? ""))\(data)")
        }
        return lines
    }

    nonisolated static func radioName(_ t: String) -> String {
        switch t {
        case CTRadioAccessTechnologyNR: return "5G"
        case CTRadioAccessTechnologyNRNSA: return "5G (paired with LTE)"
        case CTRadioAccessTechnologyLTE: return "LTE"
        case CTRadioAccessTechnologyWCDMA, CTRadioAccessTechnologyHSDPA, CTRadioAccessTechnologyHSUPA: return "3G"
        case CTRadioAccessTechnologyEdge, CTRadioAccessTechnologyGPRS: return "2G"
        case "": return "No service"
        default: return t.replacingOccurrences(of: "CTRadioAccessTechnology", with: "")
        }
    }

    nonisolated static func describe(_ path: NWPath) -> String {
        guard path.status == .satisfied else { return "No internet connection" }
        var kind = "a network"
        if path.usesInterfaceType(.wifi) {
            kind = "Wi-Fi"
        } else if path.usesInterfaceType(.cellular) {
            kind = "cellular data"
        } else if path.usesInterfaceType(.wiredEthernet) {
            kind = "a wired connection"
        }
        var notes: [String] = []
        if path.isExpensive { notes.append("metered") }
        if path.isConstrained { notes.append("Low Data Mode") }
        return "Online via \(kind)" + (notes.isEmpty ? "" : " (\(notes.joined(separator: ", ")))")
    }
}
