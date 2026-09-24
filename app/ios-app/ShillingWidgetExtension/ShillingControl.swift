import AppIntents
import SwiftUI
import WidgetKit

@main
struct ShillingWidgetBundle: WidgetBundle {
    var body: some Widget {
        ShillingReceiptControl()
    }
}

struct ShillingReceiptControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: "finance.shilling.receipt-control") {
            ControlWidgetButton(action: OpenReceiptCameraIntent()) {
                Label("Scan Receipt", systemImage: "camera.fill")
            }
        }
        .displayName("Scan Receipt")
        .description("Open Shilling to scan a receipt")
    }
}
