# Live Demonstration Guide & Evaluation Script
**iTantra — ISRO Problem Statement 26173**

---

## 1. Pre-Demo Checklist

- [ ] Two Android phones with `iTantra` installed.
- [ ] Both phones set to **Airplane Mode** with **Bluetooth ON** (or Wi-Fi Direct ON).
- [ ] Both phones have audio output volume enabled.
- [ ] Language set to **Hindi** (or selected evaluation language) on both devices.

---

## 2. Step-by-Step Demo Script

### Scenario 1: Push-To-Talk Hindi MVP Walkie-Talkie
1. Launch **iTantra** on Phone A and Phone B.
2. On Phone A, click **Connect** → select Phone B from the discovered paired devices list.
3. Observe the green connection status dot on both devices.
4. On Phone A: Hold down **HOLD TO TALK**.
5. Speak clearly into the microphone:
   > *"मुझे मदद चाहिए"*
6. Release the button.
7. **Observed on Phone A**: Text `"मुझे मदद चाहिए"` is transcribed locally in ~280ms and packet transmitted.
8. **Observed on Phone B**: Text appears in the received text box, TTS synthesizes audio in ~190ms, and Phone B audibly speaks the phrase in Hindi.

### Scenario 2: Emergency SOS Alert Mode
1. On Phone A, click the red **HIGH-PRIORITY ALERT / SOS** button.
2. Phone A transmits the text packet with `isAlert = true`.
3. **Observed on Phone B**: Phone B plays a loud dual-tone warning siren chime followed by the emergency message synthesized at maximum alarm stream volume.

### Scenario 3: Continuous Conversation Mode
1. On Phone A, select the **Continuous** radio button.
2. Speak natural sentences without pressing buttons:
   > *"स्थान सुरक्षित है"*
3. Pause for ~800ms.
4. Observe automatic sentence boundary segmentation via VAD and transmission to Phone B.

### Scenario 4: Seamless Transport Switch
1. Toggle the transport switch from **BT** to **Wi-Fi Direct**.
2. Repeat transmission to demonstrate unified pipeline decoupling.

---

## 3. Demo Recovery & Troubleshooting Procedure

- **If Bluetooth fails to connect**: Toggle Bluetooth OFF and ON in Android quick settings, re-open iTantra, and tap "Connect".
- **If microphone audio is muted**: Verify that `RECORD_AUDIO` runtime permission was granted in app settings.
- **If Phone B receives text but no sound plays**: Ensure device volume is unmuted (Alert mode will automatically maximize `STREAM_ALARM`).
