# Offline Operation Verification Protocol
**iTantra — ISRO Problem Statement 26173**

---

## 1. Zero-Internet / Airplane Mode Audit

To verify that iTantra operates completely offline with zero reliance on cloud APIs, external servers, or cellular infrastructure, execute the following audit protocol:

### Step 1: Device Radio Isolation
1. Take two Android test devices (Device A and Device B).
2. Enable **Airplane Mode** on both devices.
3. Verify that **Mobile Data** (4G/5G/LTE) is disabled.
4. Verify that **Internet Wi-Fi** (connected to an external router/gateway) is disabled.
5. Manually turn ON only the required local peer-to-peer radio:
   - For Bluetooth testing: Turn ON **Bluetooth**.
   - For Wi-Fi Direct testing: Turn ON **Wi-Fi** (do NOT connect to any Wi-Fi access point or internet router).

### Step 2: Connection & Transceiving Test
1. Launch **iTantra** on Device A and Device B.
2. Pair or connect the two devices using the in-app device scanner.
3. Select **Hindi** as the active language on both phones.
4. On Device A, hold down the **HOLD TO TALK** button and speak:
   > *"मुझे मदद चाहिए"*
5. Release the button.
6. Verify that Device A transcribes the speech locally within ~300ms.
7. Verify that Device B receives the packet, synthesizes the audio via TTS, and audibly speaks the Hindi phrase through its speaker.
8. Repeat the test with **English** and the other supported Indian languages.

### Step 3: Network Traffic & Data Consumption Audit
1. Open Android **Settings → Network & internet → Data usage**.
2. Confirm that iTantra has consumed **0.00 KB** of mobile or Wi-Fi internet data.
3. Connect Device A and Device B to a development computer via USB and run logcat monitoring:
   ```bash
   adb logcat -s iTantra AudioRecorder VadEngine SttEngine TtsEngine BluetoothTransport WifiDirectTransport
   ```
4. Confirm from logcat output that:
   - All tensor inferences executed on local ONNX/TFLite instances.
   - Sockets bind strictly to `localhost` or local Bluetooth RFCOMM UUIDs.
   - No HTTP, HTTPS, WebSocket, Firebase, or external DNS resolutions are triggered.
