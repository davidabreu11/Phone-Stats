import Foundation
import Darwin
import os

struct MemoryInfo {
    var total: UInt64
    var used: UInt64?          // system-wide; nil if iOS won't say
    var appFootprint: UInt64?  // this app's own memory use
    var appAvailable: UInt64?  // how much more this app could use
}

/// Low-level readings. Anything iOS refuses comes back as nil.
enum SystemInfo {

    static func memory() -> MemoryInfo {
        let total = ProcessInfo.processInfo.physicalMemory
        var result = MemoryInfo(total: total, used: nil, appFootprint: nil, appAvailable: nil)

        var stats = vm_statistics64()
        var count = mach_msg_type_number_t(MemoryLayout<vm_statistics64_data_t>.size / MemoryLayout<integer_t>.size)
        let kr = withUnsafeMutablePointer(to: &stats) { ptr in
            ptr.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                host_statistics64(mach_host_self(), HOST_VM_INFO64, $0, &count)
            }
        }
        if kr == KERN_SUCCESS {
            let page = UInt64(vm_kernel_page_size)
            let used = (UInt64(stats.active_count) + UInt64(stats.wire_count)
                        + UInt64(stats.compressor_page_count)) * page
            if used > 0 { result.used = min(used, total) }
        }

        var info = task_vm_info_data_t()
        var infoCount = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<integer_t>.size)
        let kr2 = withUnsafeMutablePointer(to: &info) { ptr in
            ptr.withMemoryRebound(to: integer_t.self, capacity: Int(infoCount)) {
                task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &infoCount)
            }
        }
        if kr2 == KERN_SUCCESS { result.appFootprint = UInt64(info.phys_footprint) }

        let avail = os_proc_available_memory()
        if avail > 0 { result.appAvailable = UInt64(avail) }
        return result
    }

    static func storage() -> (total: Int64, free: Int64)? {
        let url = URL(fileURLWithPath: NSHomeDirectory())
        guard let v = try? url.resourceValues(forKeys: [.volumeTotalCapacityKey,
                                                        .volumeAvailableCapacityForImportantUsageKey]),
              let total = v.volumeTotalCapacity,
              let free = v.volumeAvailableCapacityForImportantUsage else { return nil }
        return (Int64(total), free)
    }

    /// e.g. "iPhone18,2"
    static func modelIdentifier() -> String {
        var u = utsname()
        uname(&u)
        return withUnsafePointer(to: &u.machine) { ptr in
            ptr.withMemoryRebound(to: CChar.self, capacity: 1) { String(cString: $0) }
        }
    }

    static func sysctlInt(_ name: String) -> Int? {
        var value: Int32 = 0
        var size = MemoryLayout<Int32>.size
        return sysctlbyname(name, &value, &size, nil, 0) == 0 ? Int(value) : nil
    }
}

/// Measures how busy each CPU core is between two calls.
final class CpuSampler {
    private(set) var available = true
    private var previous: [[UInt32]] = []

    /// Overall and per-core busy %, or nil on the first call or if iOS refuses.
    func sample() -> (total: Double, cores: [Double])? {
        var cpuCount: natural_t = 0
        var info: processor_info_array_t?
        var infoCount: mach_msg_type_number_t = 0
        let kr = host_processor_info(mach_host_self(), PROCESSOR_CPU_LOAD_INFO, &cpuCount, &info, &infoCount)
        guard kr == KERN_SUCCESS, let info = info else {
            available = false
            return nil
        }
        var current: [[UInt32]] = []
        let stride = Int(CPU_STATE_MAX)
        for i in 0..<Int(cpuCount) {
            let b = i * stride
            current.append([
                UInt32(bitPattern: info[b + Int(CPU_STATE_USER)]),
                UInt32(bitPattern: info[b + Int(CPU_STATE_SYSTEM)]),
                UInt32(bitPattern: info[b + Int(CPU_STATE_NICE)]),
                UInt32(bitPattern: info[b + Int(CPU_STATE_IDLE)]),
            ])
        }
        vm_deallocate(mach_task_self_, vm_address_t(bitPattern: info),
                      vm_size_t(Int(infoCount) * MemoryLayout<integer_t>.stride))

        let prev = previous
        previous = current
        guard prev.count == current.count, !prev.isEmpty else { return nil }

        var busyAll: UInt64 = 0
        var totalAll: UInt64 = 0
        var cores: [Double] = []
        for (p, c) in zip(prev, current) {
            let busy = UInt64(c[0] &- p[0]) + UInt64(c[1] &- p[1]) + UInt64(c[2] &- p[2])
            let idle = UInt64(c[3] &- p[3])
            let total = busy + idle
            busyAll += busy
            totalAll += total
            cores.append(total > 0 ? Double(busy) / Double(total) * 100 : 0)
        }
        guard totalAll > 0 else { return nil }
        return (Double(busyAll) / Double(totalAll) * 100, cores)
    }
}
