# System Boundaries & Limitations
**iTantra — ISRO Problem Statement 26173**

---

## 1. Radio Hardware Boundaries

1. **Bluetooth Range**:
   - Classic Bluetooth RFCOMM operating range is determined by physical device radio class (Class 2 typically 10–30 meters line-of-sight).
   - Concrete walls, metal obstacles, and dense foliage will attenuate 2.4 GHz RF signals.
2. **Wi-Fi Direct Range**:
   - P2P Wi-Fi direct typically achieves 50–100 meters line-of-sight depending on antenna gain and transmit power.

---

## 2. Audio & Android OS Boundaries

1. **Silent / DND Policies**:
   - While Alert Mode requests exclusive high-priority audio focus and maximizes `STREAM_ALARM` application volume, certain OEM Android forks enforce strict Do Not Disturb (DND) or physical hardware mute switch restrictions that cannot be universally bypassed from userspace without root or enterprise device management permissions.
2. **Half-Duplex Channel**:
   - iTantra implements a half-duplex communication model (walkie-talkie style). Simultaneous transmissions are flagged with a `COLLISION_BUSY` state to prevent corrupted text packets.
3. **Same-Language Transceiving**:
   - In accordance with ISRO Problem Statement 26173 scope lock, translation between different languages is out of scope; speech is transcribed and re-synthesized in the selected target language.
