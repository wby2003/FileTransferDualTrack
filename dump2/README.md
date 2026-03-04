# USB+WiFi Dual-Track File Transfer

This simple Java project demonstrates a multi-channel file transfer system that can
use multiple network endpoints simultaneously (e.g. USB via `adb forward` plus
Wi-Fi) to speed up transfers from an Android phone to a desktop.

## Components

- `FileTransferServer.java` - runs on the PC, listens on a port, accepts
  concurrent connections and writes incoming parts to a file using random access.
- `FileTransferClient.java` - runs on the phone or any Java-capable host. Splits a
  file into segments and sends each over a separate TCP connection. You can
  specify multiple `<host:port>` endpoints; channels will be round-robin'd across
  them.

## Building

Compile both classes with `javac`:

```bash
javac FileTransferServer.java FileTransferClient.java
```

## Usage

Start the server on your PC:

```bash
java FileTransferServer [port] [output-directory]
```

Defaults: port `5000`, current directory.

On the Android device (or anywhere else):

1. Enable USB debugging and run:

   ```bash
   adb forward tcp:5000 tcp:5000
   ```

   This makes `127.0.0.1:5000` on the phone route to the PC via USB.

2. Run the client: specify both the USB endpoint and the PC's Wi‑Fi IP
   (if connected to the same network) separated by commas. Choose a number of
   parallel channels (e.g. 4).

```bash
java FileTransferClient 127.0.0.1:5000,192.168.0.100:5000 4 /sdcard/myfile.bin
```

The client will open 4 sockets, two over USB and two over Wi‑Fi in this case.

---

The implementation is intentionally minimal; feel free to extend with progress
reports, retries, encryption, or a GUI on Android.
