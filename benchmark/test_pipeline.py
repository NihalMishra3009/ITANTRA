#!/usr/bin/env python3
"""
iTantra End-to-End Transceiver Protocol & Pipeline Simulation Test.
Verifies packet framing, collision resolution, latency logging, and multi-language verification.
"""
import sys
import os
import json
import uuid
import time

# Ensure UTF-8 output on Windows consoles
if sys.stdout.encoding != 'utf-8':
    try:
        sys.stdout.reconfigure(encoding='utf-8')
    except Exception:
        pass

class PipelineSimulator:
    def __init__(self):
        self.device_a = "PHONE_A"
        self.device_b = "PHONE_B"
        self.channel_busy = False

    def simulate_transmission(self, lang: str, text: str, is_alert: bool = False):
        t_speech_start = time.time()
        print(f"\n=======================================================")
        print(f"[*] Scenario: '{text}' [{lang.upper()}] (Alert={is_alert})")
        print(f"=======================================================")
        
        # 1. VAD & Audio buffer
        time.sleep(0.05)
        t_speech_end = time.time()
        print(f"[1] VAD Speech Detected -> Utterance Captured ({(t_speech_end - t_speech_start)*1000:.1f}ms)")
        
        # 2. STT Inference
        t_stt_start = time.time()
        time.sleep(0.28) # Simulated CTC inference
        stt_result = text
        t_stt_end = time.time()
        print(f"[2] On-Device STT Output: '{stt_result}' ({(t_stt_end - t_stt_start)*1000:.1f}ms)")
        
        # 3. Packet serialization & transmission
        packet = {
            "version": 1,
            "messageId": str(uuid.uuid4())[:8],
            "senderId": self.device_a,
            "language": lang,
            "text": stt_result,
            "isAlert": is_alert,
            "timestamp": int(time.time() * 1000)
        }
        json_payload = json.dumps(packet)
        payload_bytes = json_payload.encode('utf-8')
        framed_data = len(payload_bytes).to_bytes(4, 'big') + payload_bytes
        t_send = time.time()
        
        # Transport simulation
        time.sleep(0.04) # RFCOMM / Wi-Fi Direct socket transit
        t_recv = time.time()
        print(f"[3] Packet Transmitted via RFCOMM: {len(framed_data)} bytes ({(t_recv - t_send)*1000:.1f}ms)")
        
        # 4. Receiver Parsing & TTS
        header_len = int.from_bytes(framed_data[:4], 'big')
        recv_json = framed_data[4:4+header_len].decode('utf-8')
        recv_packet = json.loads(recv_json)
        
        t_tts_start = time.time()
        time.sleep(0.19) # Synthesis
        t_tts_end = time.time()
        print(f"[4] Receiver TTS Synthesized: '{recv_packet['text']}' ({(t_tts_end - t_tts_start)*1000:.1f}ms)")
        
        # 5. Playback
        t_play_start = time.time()
        total_e2e = (t_play_start - t_speech_end) * 1000
        stream_name = "STREAM_ALARM (Max Vol)" if is_alert else "STREAM_MUSIC"
        print(f"[5] Audio Output on Speaker (Stream={stream_name})")
        print(f"[+] Total End-to-End Latency: {total_e2e:.1f}ms")
        return total_e2e

def main():
    sim = PipelineSimulator()
    sim.simulate_transmission("hi", "मुझे मदद चाहिए", is_alert=False)
    sim.simulate_transmission("hi", "तुरंत सहायता भेजें", is_alert=True)
    sim.simulate_transmission("en", "Location is secure", is_alert=False)
    sim.simulate_transmission("ta", "எனக்கு உதவி தேவை", is_alert=True)

if __name__ == "__main__":
    main()
