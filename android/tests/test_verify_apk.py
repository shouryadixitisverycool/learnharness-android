import hashlib
import io
import struct
import unittest
import zipfile

from android.verify_apk import load_alignments, verify_runtime


def elf(machine=183, alignment=16384):
    data = bytearray(120)
    data[:6] = b"\x7fELF\x02\x01"
    struct.pack_into("<16sHHIQQQIHHHHHH", data, 0, bytes(data[:16]), 3, machine, 1, 0, 64, 0, 0, 64, 56, 1, 0, 0, 0)
    struct.pack_into("<IIQQQQQQ", data, 64, 1, 5, 0, 0, 0, 0, 0, alignment)
    return bytes(data)


class VerifyApkTests(unittest.TestCase):
    def test_accepts_aarch64_load_aligned_for_16kb_pages(self):
        self.assertEqual(load_alignments(elf()), [16384])

    def test_rejects_wrong_architecture_and_small_page_alignment(self):
        with self.assertRaisesRegex(ValueError, "AArch64"):
            load_alignments(elf(machine=62))
        with self.assertRaisesRegex(ValueError, "16 KB"):
            load_alignments(elf(alignment=4096))

    def test_runtime_identity_and_private_path_checks(self):
        def package(name):
            data = io.BytesIO()
            with zipfile.ZipFile(data, "w") as archive:
                archive.writestr(name, "fixture")
            return data.getvalue()

        clean = package("server.js")
        verify_runtime(clean, hashlib.sha256(clean).hexdigest().encode())
        with self.assertRaisesRegex(ValueError, "profile data"):
            verify_runtime(package("data/chats/chat.json"), hashlib.sha256(package("data/chats/chat.json")).hexdigest().encode())
        with self.assertRaisesRegex(ValueError, "private file"):
            verify_runtime(package("node_modules/example/auth.json"), hashlib.sha256(package("node_modules/example/auth.json")).hexdigest().encode())
        library = package("node_modules/example/core/credentials.mjs")
        verify_runtime(library, hashlib.sha256(library).hexdigest().encode())


if __name__ == "__main__":
    unittest.main()
