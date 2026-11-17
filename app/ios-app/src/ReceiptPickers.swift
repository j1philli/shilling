import SwiftUI
import PhotosUI
import UniformTypeIdentifiers

/// A picked receipt file: its name and bytes.
struct PickedFile: Sendable {
    let name: String
    let data: Data
}

/// "Take photo", "Choose photo" and "Attach file" buttons for adding a receipt. Each hands the
/// picked file to `onPick`.
struct ReceiptSourceButtons: View {
    /// Opens the camera right away (once), e.g. for "Scan Receipt".
    var launchCamera = false
    let onPick: (PickedFile) -> Void

    @State private var launchedCamera = false
    @State private var showingCamera = false
    @State private var showingFiles = false
    @State private var photo: PhotosPickerItem?

    var body: some View {
        if UIImagePickerController.isSourceTypeAvailable(.camera) {
            Button { showingCamera = true } label: { Label("Take photo", systemImage: "camera") }
        }
        PhotosPicker(selection: $photo, matching: .images) {
            Label("Choose photo", systemImage: "photo.on.rectangle")
        }
        // The presenters live on this one row: modifiers on a group would apply to every row.
        Button { showingFiles = true } label: { Label("Attach file", systemImage: "doc") }
            .fullScreenCover(isPresented: $showingCamera) {
                CameraPicker { image in
                    Task { @MainActor in
                        if let file = await ReceiptFilePreparation.jpeg(image, name: "Receipt \(Self.timestamp()).jpg") {
                            onPick(file)
                        }
                    }
                }
                .ignoresSafeArea()
            }
            .fileImporter(isPresented: $showingFiles, allowedContentTypes: [.item]) { result in
                guard case .success(let url) = result else { return }
                Task { @MainActor in
                    if let file = try? await ReceiptFilePreparation.read(url) { onPick(file) }
                }
            }
            .onAppear {
                guard launchCamera, !launchedCamera, UIImagePickerController.isSourceTypeAvailable(.camera) else { return }
                launchedCamera = true
                showingCamera = true
            }
            .onChange(of: photo) { _, item in
                guard let item else { return }
                photo = nil
                Task {
                    guard let data = try? await item.loadTransferable(type: Data.self) else { return }
                    let ext = item.supportedContentTypes.first?.preferredFilenameExtension ?? "jpg"
                    onPick(PickedFile(name: "Photo \(Self.timestamp()).\(ext)", data: data))
                }
            }
    }

    private static func timestamp() -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd HHmmss"
        return formatter.string(from: Date())
    }
}

/// The system camera, returning the captured photo.
struct CameraPicker: UIViewControllerRepresentable {
    let onCapture: (UIImage) -> Void
    @Environment(\.dismiss) private var dismiss

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ controller: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let parent: CameraPicker

        init(_ parent: CameraPicker) { self.parent = parent }

        func imagePickerController(
            _ picker: UIImagePickerController,
            didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]
        ) {
            if let image = info[.originalImage] as? UIImage { parent.onCapture(image) }
            parent.dismiss()
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            parent.dismiss()
        }
    }
}
