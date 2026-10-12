#!/usr/bin/env python3
"""
Fake Lag Tool - Drops small UDP inbound packets to simulate network lag.
Requires: pip install pydivert pywin32
"""

import sys
import os
import threading
import winsound
import ctypes
import time
from tkinter import Tk, Button, Label

# ---------- Admin elevation ----------
def is_admin():
    try:
        return ctypes.windll.shell32.IsUserAnAdmin()
    except Exception:
        return False

def elevate():
    ctypes.windll.shell32.ShellExecuteW(
        None, "runas", sys.executable, f'"{__file__}"', None, 1
    )

if not is_admin():
    elevate()
    sys.exit(0)

# ---------- Imports that need admin ----------
try:
    import pydivert
except ImportError:
    winsound.Beep(300, 500)
    print("[ERROR] pydivert not installed. Run: pip install pydivert")
    sys.exit(1)

# ---------- Beep helper (threaded to avoid blocking GUI) ----------
def beep(freq, duration=200):
    def _do():
        try:
            winsound.Beep(int(freq), int(duration))
        except Exception:
            pass
    threading.Thread(target=_do, daemon=True).start()

# ---------- Packet filter thread ----------
class FakeLagEngine:
    """
    Uses WinDivert to capture and drop UDP inbound packets.
    Payload size filter: UDP datagram length 58-258 means payload 50-250 bytes
    (UDP header = 8 bytes).
    """
    def __init__(self):
        self._running = False
        self._handle = None
        self._thread = None
        self._lock = threading.Lock()
        self._error_callback = None

    @property
    def running(self):
        return self._running

    def set_error_callback(self, cb):
        self._error_callback = cb

    def start(self):
        with self._lock:
            if self._running:
                return
            self._running = True
            self._thread = threading.Thread(target=self._loop, daemon=True)
            self._thread.start()

    def stop(self):
        with self._lock:
            self._running = False
        if self._thread:
            self._thread.join(timeout=3.0)
            self._thread = None

    def _loop(self):
        # Minimal filter — do size filtering in Python for max compatibility
        filter_str = "inbound and udp"
        print(f"[LOG] Opening WinDivert with filter: {filter_str!r}")
        try:
            with pydivert.WinDivert(filter_str) as w:
                print("[LOG] WinDivert opened OK, starting capture loop...")
                # Set queue time to 100ms so recv() returns promptly
                try:
                    w.set_param(pydivert.Param.QUEUE_TIME, 100)
                except Exception:
                    pass  # not all versions support this; fine
                while self._running:
                    try:
                        packet = w.recv()
                    except Exception:
                        # recv may fail if handle closed during stop
                        break
                    if packet is None:
                        continue
                    # Filter by UDP payload size in Python (more reliable than filter string)
                    pkt_len = len(packet.raw) if hasattr(packet, 'raw') else len(packet.payload)
                    if 50 <= pkt_len <= 250:
                        # Don't call w.send(packet) -> packet is DROPPED
                        pass
                    else:
                        # Forward packets outside our target range
                        try:
                            w.send(packet)
                        except Exception:
                            pass
        except OSError as e:
            print(f"[ERROR] OSError: {e}")
            beep(300, 500)
            self._running = False
            if self._error_callback:
                self._error_callback()
        except Exception as e:
            print(f"[ERROR] {type(e).__name__}: {e}")
            beep(300, 500)
            self._running = False
            if self._error_callback:
                self._error_callback()
        print("[LOG] Capture loop ended.")


# ---------- GUI ----------
class FakeLagApp:
    def __init__(self):
        self.win = Tk()
        self.win.title("Fake Lag Tool")
        self.win.geometry("440x340")
        self.win.configure(bg="#1a1a1a")
        self.win.resizable(False, False)

        # Center on screen
        self.win.update_idletasks()
        w, h = 440, 340
        sw = self.win.winfo_screenwidth()
        sh = self.win.winfo_screenheight()
        x = (sw - w) // 2
        y = (sh - h) // 2
        self.win.geometry(f"{w}x{h}+{x}+{y}")

        # Bring to top
        self.win.attributes("-topmost", True)
        self.win.after(500, lambda: self.win.attributes("-topmost", False))

        self.engine = FakeLagEngine()
        self.engine.set_error_callback(self._on_engine_error)
        self._on = False

        self._build_ui()

    def _build_ui(self):
        # Title
        Label(
            self.win, text="FAKE LAG TOOL",
            font=("Consolas", 16, "bold"), fg="#888888", bg="#1a1a1a",
        ).pack(pady=(40, 10))

        # Status
        self.status_label = Label(
            self.win, text="Status: IDLE",
            font=("Consolas", 10), fg="#666666", bg="#1a1a1a",
        )
        self.status_label.pack(pady=(0, 25))

        # Big toggle button
        self.btn = Button(
            self.win, text="FAKE LAG: OFF",
            font=("Consolas", 18, "bold"),
            fg="white", bg="#555555",
            activebackground="#444444", activeforeground="white",
            relief="flat", bd=0,
            padx=35, pady=22,
            cursor="hand2",
            command=self.toggle,
        )
        self.btn.pack(pady=10)

        # Subtitle
        Label(
            self.win,
            text="Drops UDP inbound packets (payload 50–250 bytes)",
            font=("Consolas", 8), fg="#555555", bg="#1a1a1a",
        ).pack(side="bottom", pady=20)

    def toggle(self):
        if not self._on:
            self._start()
        else:
            self._stop()

    def _start(self):
        try:
            self.engine.start()
            self._on = True
            self.btn.config(
                text="FAKE LAG: ON",
                bg="#2e8b2e", activebackground="#267326",
            )
            self.status_label.config(
                text="Status: ACTIVE — dropping packets", fg="#55cc55",
            )
            beep(600, 250)
        except Exception as e:
            beep(300, 500)
            self.status_label.config(text=f"Error: {e}", fg="#cc3333")

    def _stop(self):
        self.engine.stop()
        self._on = False
        self.btn.config(
            text="FAKE LAG: OFF",
            bg="#555555", activebackground="#444444",
        )
        self.status_label.config(text="Status: IDLE", fg="#666666")
        beep(400, 250)

    def _on_engine_error(self):
        self.win.after(0, self._handle_engine_error)

    def _handle_engine_error(self):
        self._on = False
        self.btn.config(
            text="FAKE LAG: OFF",
            bg="#555555", activebackground="#444444",
        )
        self.status_label.config(
            text="Status: ERROR — check console", fg="#cc3333",
        )

    def run(self):
        self.win.mainloop()


# ---------- Entry point ----------
if __name__ == "__main__":
    app = FakeLagApp()
    app.run()
