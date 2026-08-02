import AppKit
import CoreBluetooth
import Foundation

final class Scanner: NSObject, NSApplicationDelegate, CBCentralManagerDelegate {
    private let output = URL(fileURLWithPath: "/tmp/pelopower-ble-scan.txt")
    private var central: CBCentralManager!
    private var discoveries = 0
    private var matches = 0

    func applicationDidFinishLaunching(_ notification: Notification) {
        try? "PeloPower BLE scan starting\n".write(to: output, atomically: true, encoding: .utf8)
        central = CBCentralManager(delegate: self, queue: nil)
        Timer.scheduledTimer(withTimeInterval: 20, repeats: false) { [weak self] _ in
            self?.finish()
        }
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        append("Bluetooth state=\(central.state.rawValue) authorization=\(CBManager.authorization.rawValue)")
        if central.state == .poweredOn {
            central.scanForPeripherals(
                withServices: nil,
                options: [CBCentralManagerScanOptionAllowDuplicatesKey: false]
            )
        }
    }

    func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        discoveries += 1
        let services = advertisementData[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID] ?? []
        let manufacturer = advertisementData[CBAdvertisementDataManufacturerDataKey] as? Data
        let advertisedName = advertisementData[CBAdvertisementDataLocalNameKey] as? String
        let name = advertisedName ?? peripheral.name ?? "unnamed"
        // CoreBluetooth includes the two-byte company identifier at the start of this field.
        // PeloPower uses the test identifier 0xFFFF, so decoding the whole value as UTF-8 fails.
        let marker = Data("PeloPower".utf8)
        let hasPeloPowerMarker = manufacturer?.range(of: marker) != nil
        let manufacturerHex = manufacturer?.map { String(format: "%02x", $0) }.joined() ?? ""
        let isMatch = services.contains(CBUUID(string: "1818"))
            || services.contains(CBUUID(string: "1816"))
            || services.contains(CBUUID(string: "1849"))
            || services.contains(CBUUID(string: "1855"))
            || name.localizedCaseInsensitiveContains("PLTN")
            || name.localizedCaseInsensitiveContains("PeloPower")
            || hasPeloPowerMarker
        if isMatch {
            matches += 1
            append("MATCH name=\(name) rssi=\(RSSI) services=\(services) manufacturer=\(manufacturerHex)")
        }
    }

    private func append(_ line: String) {
        guard let data = "\(line)\n".data(using: .utf8) else { return }
        if !FileManager.default.fileExists(atPath: output.path) {
            FileManager.default.createFile(atPath: output.path, contents: nil)
        }
        guard let handle = try? FileHandle(forWritingTo: output) else { return }
        defer { try? handle.close() }
        _ = try? handle.seekToEnd()
        try? handle.write(contentsOf: data)
    }

    private func finish() {
        central?.stopScan()
        append("Finished discoveries=\(discoveries) matches=\(matches)")
        NSApplication.shared.terminate(nil)
    }
}

let app = NSApplication.shared
let scanner = Scanner()
app.setActivationPolicy(.accessory)
app.delegate = scanner
app.activate(ignoringOtherApps: true)
app.run()
