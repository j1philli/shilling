import SwiftUI
import PhotosUI
import UniformTypeIdentifiers

/// A picked receipt file: its name and bytes.
struct PickedFile {
    let name: String
    let data: Data
}

/// "Take photo", "Choose photo" and "Attach file" buttons for adding a receipt. Each hands the
/// picked file to `onPick`.
struct ReceiptSourceButtons: View {
    let onPick: (PickedFile) -> Void

    @State private var showingCamera = false
    @State private var showingFiles = false
    @State private var photo: PhotosPickerItem?

    var body: some View {
        Group {
            if UIImagePickerController.isSourceTypeAvailable(.camera) {
                Button { showingCamera = true } label: { Label("Take photo", systemImage: "camera") }
            }
            PhotosPicker(selection: $photo, matching: .images) {
                Label("Choose photo", systemImage: "photo.on.rectangle")
            }
            Button { showingFiles = true } label: { Label("Attach file", systemImage: "doc") }
        }
        .fullScreenCover(isPresented: $showingCamera) {
            CameraPicker { image in
                if let data = image.jpegData(compressionQuality: 0.85) {
                    onPick(PickedFile(name: "Receipt \(Self.timestamp()).jpg", data: data))
                }
            }
            .ignoresSafeArea()
        }
        .fileImporter(isPresented: $showingFiles, allowedContentTypes: [.item]) { result in
            guard case .success(let url) = result else { return }
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            if let data = try? Data(contentsOf: url) {
                onPick(PickedFile(name: url.lastPathComponent, data: data))
            }
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
