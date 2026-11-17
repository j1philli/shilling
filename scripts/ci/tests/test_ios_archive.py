"""Exercise the archive shape required by Xcode's App Store exporter."""
import importlib.util
from pathlib import Path
import plistlib
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("ios_archive", Path(__file__).resolve().parents[1] / "check-ios-archive.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ArchiveTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.archive = Path(self.temp.name)
        self.app = self.archive / "Products/Applications/ios-app.app"
        self.write(self.archive / "Info.plist", {"ApplicationProperties": {"ApplicationPath": "Applications/ios-app.app"}})
        self.write(self.app / "Info.plist", {"CFBundleShortVersionString": "0.1.1", "CFBundleVersion": "2"})
        self.extension = self.app / "PlugIns/Widget.appex/Info.plist"
        self.write(self.extension, {"CFBundleShortVersionString": "0.1.1", "CFBundleVersion": "2"})

    def write(self, path, data):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(plistlib.dumps(data))

    def test_application_archive_passes(self):
        module.check_archive(self.archive, "0.1.1")

    def test_generic_archive_fails(self):
        self.write(self.archive / "Info.plist", {"ArchiveVersion": 2})
        with self.assertRaisesRegex(ValueError, "generic archive"):
            module.check_archive(self.archive, "0.1.1")

    def test_stray_linker_framework_fails(self):
        (self.app.parent / "WebRTCFramework").mkdir()
        with self.assertRaisesRegex(ValueError, "extra products"):
            module.check_archive(self.archive, "0.1.1")

    def test_stale_widget_version_fails(self):
        self.write(self.extension, {"CFBundleShortVersionString": "0.1.0", "CFBundleVersion": "2"})
        with self.assertRaisesRegex(ValueError, "Wrong product version"):
            module.check_archive(self.archive, "0.1.1")

    def test_stale_widget_build_fails(self):
        self.write(self.extension, {"CFBundleShortVersionString": "0.1.1", "CFBundleVersion": "1"})
        with self.assertRaisesRegex(ValueError, "build numbers differ"):
            module.check_archive(self.archive, "0.1.1")
