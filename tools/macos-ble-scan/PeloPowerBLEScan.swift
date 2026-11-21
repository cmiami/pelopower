import AppKit
import CoreBluetooth
import Foundation

final class Scanner: NSObject, NSApplicationDelegate, CBCentralManagerDelegate, CBPeripheralDelegate {
    private let output = URL(fileURLWithPath: "/tmp/pelopower-ble-scan.txt")
    private var central: CBCentralManager!
    private var target: CBPeripheral?
    private var connectScheduled = false
    private var discoveries = 0
    private var matches = 0
    private var notifications = 0
    private let scanOnly = ProcessInfo.processInfo.environment["PELOPOWER_SCAN_ONLY"] == "1"

    func applicationDidFinishLaunching(_ notification: Notification) {
        try? "PeloPower BLE scan starting\n".write(to: output, atomically: true, encoding: .utf8)
        central = CBCentralManager(delegate: self, queue: nil)
        let duration = Double(ProcessInfo.processInfo.environment["PELOPOWER_SCAN_SECONDS"] ?? "20")
            ?? 20
        Timer.scheduledTimer(withTimeInterval: duration, repeats: false) { [weak self] _ in
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
            append("MATCH id=\(peripheral.identifier) name=\(name) rssi=\(RSSI) services=\(services) manufacturer=\(manufacturerHex)")
            if !scanOnly && target == nil && services.contains(CBUUID(string: "1818")) {
                target = peripheral
                peripheral.delegate = self
            }
            if target != nil && !connectScheduled {
                connectScheduled = true
                // Allow the active scanner to receive the companion advertisement and scan
                // response before connecting to one of the two dedicated sensor advertisements.
                Timer.scheduledTimer(withTimeInterval: 2, repeats: false) { [weak self] _ in
                    guard let self, let target = self.target else { return }
                    self.central.stopScan()
                    self.append("CONNECTING id=\(target.identifier)")
                    self.central.connect(target)
                }
            }
        }
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        append("CONNECTED name=\(peripheral.name ?? "unnamed")")
        peripheral.discoverServices(nil)
    }

    func centralManager(
        _ central: CBCentralManager,
        didFailToConnect peripheral: CBPeripheral,
        error: Error?
    ) {
        append("CONNECT FAILED error=\(String(describing: error))")
    }

    func centralManager(
        _ central: CBCentralManager,
        didDisconnectPeripheral peripheral: CBPeripheral,
        error: Error?
    ) {
        append("DISCONNECTED error=\(String(describing: error))")
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        if let error {
            append("SERVICE DISCOVERY FAILED error=\(error)")
            return
        }
        for service in peripheral.services ?? [] {
            append("SERVICE uuid=\(service.uuid)")
            if service.uuid == CBUUID(string: "1818")
                || service.uuid == CBUUID(string: "1816")
                || service.uuid == CBUUID(string: "180A") {
                peripheral.discoverCharacteristics(nil, for: service)
            }
        }
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didDiscoverCharacteristicsFor service: CBService,
        error: Error?
    ) {
        if let error {
            append("CHARACTERISTIC DISCOVERY FAILED service=\(service.uuid) error=\(error)")
            return
        }
        for characteristic in service.characteristics ?? [] {
            append("CHARACTERISTIC service=\(service.uuid) uuid=\(characteristic.uuid) properties=\(characteristic.properties.rawValue)")
            if characteristic.properties.contains(.read) {
                peripheral.readValue(for: characteristic)
            }
            if characteristic.properties.contains(.notify) {
                peripheral.setNotifyValue(true, for: characteristic)
            }
        }
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateNotificationStateFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        append("SUBSCRIPTION uuid=\(characteristic.uuid) active=\(characteristic.isNotifying) error=\(String(describing: error))")
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateValueFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        let bytes = characteristic.value ?? Data()
        let hex = bytes.map { String(format: "%02x", $0) }.joined()
        if characteristic.isNotifying {
            notifications += 1
            append("NOTIFICATION uuid=\(characteristic.uuid) value=\(hex)")
        } else {
            append("READ uuid=\(characteristic.uuid) value=\(hex)")
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
        append("Finished discoveries=\(discoveries) matches=\(matches) notifications=\(notifications)")
        if let target {
            central?.cancelPeripheralConnection(target)
            Timer.scheduledTimer(withTimeInterval: 0.5, repeats: false) { _ in
                NSApplication.shared.terminate(nil)
            }
        } else {
            NSApplication.shared.terminate(nil)
        }
    }
}

let app = NSApplication.shared
let scanner = Scanner()
app.setActivationPolicy(.accessory)
app.delegate = scanner
app.activate(ignoringOtherApps: true)
app.run()
