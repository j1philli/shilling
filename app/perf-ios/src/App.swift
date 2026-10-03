import SwiftUI
import UIKit
import KotlinModules

@main
struct ShillingPerformanceApp: App {
    var body: some Scene {
        WindowGroup {
            if ProcessInfo.processInfo.arguments.contains("--perf-ui") {
                NativeUiPerformanceView().ignoresSafeArea()
            } else {
                PerformanceView().ignoresSafeArea()
            }
        }
    }
}

private struct PerformanceView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        if ProcessInfo.processInfo.arguments.contains("--perf-soak") { P2pSampler.start() }
        return P2pPerformanceKt.PerformanceViewController()
    }
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

// Opt-in process measurements in the isolated benchmark app only.
@MainActor private enum P2pSampler {
    static var timer: Timer?
    static var observers: [NSObjectProtocol] = []
    static let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("p2p-soak.jsonl")
    static func start() {
        guard timer == nil else { return }
        try? Data().write(to: url)
        sample("start")
        timer = Timer.scheduledTimer(withTimeInterval: 10, repeats: true) { _ in
            MainActor.assumeIsolated { sample("periodic") }
        }
        for name in [UIApplication.didEnterBackgroundNotification, UIApplication.didBecomeActiveNotification] {
            observers.append(NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) { _ in
                MainActor.assumeIsolated { sample(name.rawValue) }
            })
        }
    }
    static func sample(_ event: String) {
        var usage = rusage()
        getrusage(RUSAGE_SELF, &usage)
        let cpu = Double(usage.ru_utime.tv_sec + usage.ru_stime.tv_sec) + Double(usage.ru_utime.tv_usec + usage.ru_stime.tv_usec) / 1_000_000
        var info = mach_task_basic_info()
        var count = mach_msg_type_number_t(MemoryLayout<mach_task_basic_info>.size / MemoryLayout<integer_t>.size)
        let result = withUnsafeMutablePointer(to: &info) { pointer in
            pointer.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                task_info(mach_task_self_, task_flavor_t(MACH_TASK_BASIC_INFO), $0, &count)
            }
        }
        let record: [String: Any] = ["event": event, "wallSeconds": Date().timeIntervalSince1970,
            "cpuSeconds": cpu, "residentMiB": result == KERN_SUCCESS ? Double(info.resident_size) / 1_048_576 : -1,
            "thermalState": ProcessInfo.processInfo.thermalState.rawValue]
        guard var data = try? JSONSerialization.data(withJSONObject: record), let file = try? FileHandle(forWritingTo: url) else { return }
        data.append(10)
        file.seekToEndOfFile(); file.write(data); try? file.close()
    }
}
