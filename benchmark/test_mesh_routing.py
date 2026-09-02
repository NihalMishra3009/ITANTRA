#!/usr/bin/env python3
"""
iTantra Multi-Node Offline Mesh & Store-and-Forward Routing Simulation.
Tests: Node A -> Node B -> Node C -> Node B offline/online -> multi-hop -> destination unavailable -> store & forward -> reconnect -> ACK delivery.
"""
import sys
import time
import json
import uuid
import hashlib

if sys.stdout.encoding != 'utf-8':
    try:
        sys.stdout.reconfigure(encoding='utf-8')
    except Exception:
        pass

SECRET_KEY = "ITANTRA_OFFLINE_SECRET_26173"

class Node:
    def __init__(self, node_id: str):
        self.node_id = node_id
        self.is_online = True
        self.outbox = [] # Store-and-forward queue
        self.seen_messages = set()
        self.received_messages = []
        self.received_acks = set()

    def sign_packet(self, packet: dict) -> str:
        raw = f"{packet['version']}:{packet['messageId']}:{packet['senderId']}:{packet['recipientId']}:{packet['type']}:{packet['language']}:{packet['text']}:{packet['isAlert']}:{packet['timestamp']}:{packet['hopCount']}:{SECRET_KEY}"
        return hashlib.sha256(raw.encode('utf-8')).hexdigest()[:8]

    def create_packet(self, recipient_id: str, lang: str, text: str, is_alert: bool = False, ptype: str = "DATA"):
        packet = {
            "version": 2,
            "messageId": str(uuid.uuid4())[:8],
            "senderId": self.node_id,
            "recipientId": recipient_id,
            "type": ptype,
            "language": lang,
            "text": text,
            "isAlert": is_alert,
            "timestamp": int(time.time() * 1000),
            "hopCount": 0,
            "maxHops": 3,
            "ttlMs": 300000
        }
        packet["checksum"] = self.sign_packet(packet)
        return packet

    def receive_packet(self, packet: dict, network, from_neighbor: str):
        if not self.is_online:
            return # Node offline, cannot receive

        # 1. Ignore packets originated by self (loopback filter)
        if packet["senderId"] == self.node_id and packet["type"] != "ACK":
            return

        # 2. Verify Checksum
        expected_sig = self.sign_packet(packet)
        if packet.get("checksum") != expected_sig:
            print(f"[{self.node_id}] REJECT: Tampered checksum on {packet['messageId']}")
            return

        # 3. Deduplication
        dedup_key = packet["messageId"]
        if dedup_key in self.seen_messages:
            return
        self.seen_messages.add(dedup_key)

        # 4. Handle ACK
        if packet["type"] == "ACK":
            target_id = packet["text"].replace("ACK:", "")
            if packet["recipientId"] == self.node_id:
                print(f"[{self.node_id}] ACK RECEIVED for message {target_id} from {packet['senderId']}")
                self.received_acks.add(target_id)
                self.outbox = [m for m in self.outbox if m["messageId"] != target_id]
                return
            else:
                # Forward ACK to next hop
                if packet["hopCount"] < packet["maxHops"]:
                    fwd_ack = dict(packet)
                    fwd_ack["hopCount"] += 1
                    fwd_ack["checksum"] = self.sign_packet(fwd_ack)
                    print(f"[{self.node_id}] RELAYING ACK for {target_id} towards {packet['recipientId']}")
                    network.route(self.node_id, fwd_ack, exclude_neighbor=from_neighbor)
                return

        # 5. Check Destination
        if packet["recipientId"] == "*" or packet["recipientId"] == self.node_id:
            print(f"[{self.node_id}] DELIVERED: '{packet['text']}' [{packet['language']}] from {packet['senderId']} (Hops: {packet['hopCount']})")
            self.received_messages.append(packet)

            # Send back ACK if unicast
            if packet["recipientId"] != "*":
                ack_packet = {
                    "version": 2,
                    "messageId": "ack_" + packet["messageId"],
                    "senderId": self.node_id,
                    "recipientId": packet["senderId"],
                    "type": "ACK",
                    "language": packet["language"],
                    "text": "ACK:" + packet["messageId"],
                    "isAlert": False,
                    "timestamp": int(time.time() * 1000),
                    "hopCount": 0,
                    "maxHops": 3,
                    "ttlMs": 300000
                }
                ack_packet["checksum"] = self.sign_packet(ack_packet)
                network.route(self.node_id, ack_packet)
        else:
            # 6. Multi-Hop Intermediate Forwarding
            if packet["hopCount"] < packet["maxHops"]:
                fwd_packet = dict(packet)
                fwd_packet["hopCount"] += 1
                fwd_packet["type"] = "RELAY"
                fwd_packet["checksum"] = self.sign_packet(fwd_packet)
                print(f"[{self.node_id}] RELAYING message {packet['messageId']} towards {packet['recipientId']} (Hop {fwd_packet['hopCount']})")
                network.route(self.node_id, fwd_packet, exclude_neighbor=from_neighbor)
            else:
                print(f"[{self.node_id}] DROPPED: Max hops exceeded for {packet['messageId']}")

    def queue_for_store_and_forward(self, packet: dict):
        if not any(m["messageId"] == packet["messageId"] for m in self.outbox):
            self.outbox.append(packet)
            print(f"[{self.node_id}] STORE & FORWARD: Message {packet['messageId']} stored in local outbox (Destination offline)")

    def flush_outbox(self, network):
        if not self.is_online or not self.outbox:
            return
        print(f"[{self.node_id}] RECONNECTED: Flushing {len(self.outbox)} stored messages from outbox...")
        pending = list(self.outbox)
        for packet in pending:
            network.route(self.node_id, packet)

class MeshNetwork:
    def __init__(self):
        self.nodes = {}
        self.topology = {} # node_id -> set(neighbor_ids)

    def add_node(self, node: Node):
        self.nodes[node.node_id] = node
        self.topology[node.node_id] = set()

    def add_link(self, node1: str, node2: str):
        self.topology[node1].add(node2)
        self.topology[node2].add(node1)

    def route(self, from_node: str, packet: dict, exclude_neighbor: str = None):
        neighbors = self.topology.get(from_node, set())
        reached = False
        for n_id in neighbors:
            if n_id == exclude_neighbor:
                continue
            neighbor = self.nodes.get(n_id)
            if neighbor and neighbor.is_online:
                reached = True
                neighbor.receive_packet(packet, self, from_neighbor=from_node)

        if not reached and packet["senderId"] == from_node and packet["type"] != "ACK":
            self.nodes[from_node].queue_for_store_and_forward(packet)

def main():
    print("=" * 80)
    print("iTantra Node-to-Node Mesh & Store-and-Forward Verification Test")
    print("=" * 80)

    net = MeshNetwork()
    node_a = Node("NODE_A")
    node_b = Node("NODE_B")
    node_c = Node("NODE_C")

    net.add_node(node_a)
    net.add_node(node_b)
    net.add_node(node_c)

    # Topology: Linear Chain (A <-> B <-> C). A cannot reach C directly.
    net.add_link("NODE_A", "NODE_B")
    net.add_link("NODE_B", "NODE_C")

    print("\n--- [TEST 1] Multi-Hop Transmission (Node A -> Node B -> Node C) ---")
    pkt1 = node_a.create_packet(recipient_id="NODE_C", lang="hi", text="रास्ता साफ है तुरंत आगे बढ़ें")
    net.route("NODE_A", pkt1)

    assert len(node_c.received_messages) == 1, "Node C must receive multi-hop message"
    assert pkt1["messageId"] in node_a.received_acks, "Node A must receive ACK"
    print("[+] Test 1 Passed: 2-Hop delivery and End-to-End ACK verified.")

    print("\n--- [TEST 2] Intermediate Node Offline (Node B Goes Offline) ---")
    node_b.is_online = False
    print("[!] NODE_B is now OFFLINE (Link Broken)")

    pkt2 = node_a.create_packet(recipient_id="NODE_C", lang="hi", text="आपातकालीन राहत सामग्री पहुंचाई जाए")
    net.route("NODE_A", pkt2)

    assert len(node_a.outbox) == 1, "Message must be saved in NODE_A store & forward outbox"
    print("[+] Test 2 Passed: Message safely stored in outbox during network partition.")

    print("\n--- [TEST 3] Reconnection & Store-and-Forward Automatic Delivery ---")
    node_b.is_online = True
    print("[*] NODE_B is now ONLINE again (Link Restored)")
    node_a.flush_outbox(net)

    assert len(node_c.received_messages) == 2, "Node C must receive buffered message after reconnect"
    assert pkt2["messageId"] in node_a.received_acks, "Node A must receive ACK after flush"
    print("[+] Test 3 Passed: Automatic store-and-forward delivery and ACK verified.")

    print("\n--- [TEST 4] Duplicate Packet Suppression & Tamper Protection ---")
    dup_pkt = dict(pkt1)
    node_c.receive_packet(dup_pkt, net, from_neighbor="NODE_B")
    assert len(node_c.received_messages) == 2, "Duplicate message must be filtered out"

    tampered_pkt = dict(pkt1)
    tampered_pkt["text"] = "TAMPERED TEXT"
    node_c.receive_packet(tampered_pkt, net, from_neighbor="NODE_B")
    assert len(node_c.received_messages) == 2, "Tampered message must be rejected"
    print("[+] Test 4 Passed: Duplicate suppression and cryptographic integrity verified.")

    print("\n" + "=" * 80)
    print("ALL 4 MESH ROUTING & STORE-AND-FORWARD TESTS PASSED SUCCESSFULLY (100%)")
    print("=" * 80)

if __name__ == "__main__":
    main()
