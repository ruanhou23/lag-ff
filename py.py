import ctypes
import sys
import threading
import tkinter as tk
import winsound

# === TỰ ĐỘNG XIN QUYỀN ADMIN ===
def request_admin():
    try:
        is_admin = ctypes.windll.shell32.IsUserAnAdmin()
    except:
        is_admin = False
    if not is_admin:
        ctypes.windll.shell32.ShellExecuteW(None, "runas", sys.executable, " ".join(sys.argv), None, 1)
        sys.exit()

request_admin()

# === NHẬP THƯ VIỆN (SAU KHI CÓ ADMIN) ===
import pydivert

# === BIẾN TOÀN CỤC ===
is_fake_lag_on = False
lag_thread = None
stop_event = threading.Event()

def beep(freq, dur=200):
    try:
        winsound.Beep(freq, dur)
    except:
        pass

# === LUỒNG DROP GÓI UDP (có timeout để thoát nhanh) ===
def drop_udp_packets(stop_flag):
    # Lọc UDP inbound có payload 50-250 byte
    filter_str = "udp and inbound and udp.PayloadLength >= 50 and udp.PayloadLength <= 250"
    try:
        with pydivert.WinDivert(filter_str) as w:
            while not stop_flag.is_set():
                try:
                    # timeout 0.5s giúp kiểm tra stop_flag thường xuyên
                    packet = w.recv(timeout=0.5)
                    if packet is None:
                        continue  # timeout, quay lại kiểm tra stop_flag
                    # Không gọi w.send(packet) -> drop gói
                except pydivert.WinDivertException as e:
                    print(f"Lỗi WinDivert: {e}")
                    beep(300, 500)
                    break
                except Exception as e:
                    print(f"Lỗi khác: {e}")
                    beep(300, 500)
                    break
    except Exception as e:
        beep(300, 500)
        print(f"Không thể khởi tạo WinDivert: {e}")

def turn_on():
    global is_fake_lag_on, lag_thread, stop_event
    if is_fake_lag_on:
        return
    stop_event.clear()
    lag_thread = threading.Thread(target=drop_udp_packets, args=(stop_event,), daemon=True)
    lag_thread.start()
    is_fake_lag_on = True
    beep(600, 200)

def turn_off():
    global is_fake_lag_on, stop_event
    if not is_fake_lag_on:
        return
    stop_event.set()      # báo hiệu dừng luồng
    # Không cần join() vì thread daemon sẽ tự giải phóng khi stop_flag được set + timeout
    is_fake_lag_on = False
    beep(400, 200)

def toggle():
    if is_fake_lag_on:
        turn_off()
        btn.config(text="FAKE LAG: OFF", bg="#3f3f3f")   # nền xám
    else:
        turn_on()
        btn.config(text="FAKE LAG: ON", bg="#1f6d1f")    # nền xanh

# === GIAO DIỆN TKINTER ===
root = tk.Tk()
root.title("Fake Lag Tool")
root.configure(bg="black")
root.geometry("400x250")
root.resizable(False, False)

btn = tk.Button(
    root,
    text="FAKE LAG: OFF",
    font=("Arial", 20, "bold"),
    bg="#3f3f3f",
    fg="white",
    activebackground="#555555",
    activeforeground="white",
    padx=40,
    pady=30,
    borderwidth=0,
    command=toggle
)
btn.pack(expand=True, fill="both", padx=20, pady=20)

info = tk.Label(root, text="Drop các gói UDP inbound (payload 50-250 byte)", font=("Arial", 9), bg="black", fg="#888888")
info.pack(side="bottom", pady=5)

root.mainloop()