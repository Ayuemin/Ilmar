"""Exercise the shipped launcher source: handshake, session isolation and descendant cancellation."""
import os
from pathlib import Path
import signal
import subprocess
import tempfile
import time
import unittest


class ProcessGroupTest(unittest.TestCase):
    def test_cancellation_stops_shell_and_child(self):
        root = Path(__file__).resolve().parent.parent
        with tempfile.TemporaryDirectory() as directory:
            executable = Path(directory) / "ilmar-process"
            subprocess.run(["cc", "-O2", str(root / "app/src/main/cpp/process_group.c"), "-o", str(executable)], check=True)
            process = subprocess.Popen([str(executable), "/bin/sh", "-c", "sleep 60 & echo CHILD=$!; wait"], stdout=subprocess.PIPE, text=True)
            child = None
            try:
                header = process.stdout.readline().strip()
                self.assertEqual(header, f"ILMAR_PROCESS_GROUP={process.pid}")
                child = int(process.stdout.readline().strip().split("=")[1])
                self.assertEqual(os.getpgid(child), process.pid)
                self.assertNotEqual(os.getpgrp(), process.pid)
                os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=5)
                deadline = time.monotonic() + 3
                while time.monotonic() < deadline:
                    status = Path(f"/proc/{child}/stat")
                    if not status.exists() or status.read_text().split()[2] == "Z":
                        break
                    time.sleep(0.02)
                else:
                    self.fail("Child remained running after process-group cancellation")
            finally:
                if process.poll() is None:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait(timeout=5)
                process.stdout.close()


if __name__ == "__main__":
    unittest.main()
