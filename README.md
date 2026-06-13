# Meshward

<p align="center">
  <img src="docs/assets/meshward-logo.png" width="150" alt="Meshward logo">
</p>

<p align="center">
  <strong>Chat beyond the usual path.</strong>
</p>

<p align="center">
  An open Android messenger for nearby communication and MeshCore networks.
</p>

---

Meshward is a local-first messenger that keeps working even when the internet is unavailable.

Nearby phones can discover each other and exchange messages directly over Bluetooth Low Energy (BLE). Messages are not limited to direct Bluetooth range: other phones and small relay devices can forward them across the local mesh.

Meshward can work as a standalone nearby messenger. No mobile data, cloud account, or central server is required.

When a [MeshCore](https://meshcore.io/) gateway is available nearby, Meshward can also connect to the wider LoRa network. A single gateway can provide MeshCore access to multiple users without requiring every phone to carry its own radio.

This makes Meshward both an independent local messenger and an open companion app for MeshCore.

---

## Screenshots

<p align="center">
  <img src="docs/screenshots/chats.png" width="220" alt="Meshward chats">
  &nbsp;&nbsp;
  <img src="docs/screenshots/nearby.png" width="220" alt="Nearby Meshward nodes">
  &nbsp;&nbsp;
  <img src="docs/screenshots/network.png" width="220" alt="Meshward network map">
</p>

<p align="center">
  <sub>Chats · Nearby nodes · Network topology</sub>
</p>

---

## Why Meshward?

### Communicate without relying on the internet

Meshward does not need a cloud backend to exchange nearby messages.

It can remain useful during outages, at crowded events, while travelling, or anywhere conventional connectivity is unreliable or undesirable.

### Reach beyond direct Bluetooth range

Messages are not limited to one Bluetooth connection.

Nearby devices can relay packets for each other, allowing communication to move through a local multi-hop mesh.

```text
Alice  ↔  relay  ↔  Bob  ↔  Carol
```

### Connect to the wider MeshCore network

A reachable gateway can bridge nearby Meshward users into a MeshCore LoRa network.

One gateway can serve multiple nearby phones, so every user does not need to carry a dedicated LoRa device.

```text
                  phone
                    ↕
phone  ↔  local gateway  ↔  MeshCore
                    ↕
                  tablet
```

### Extend the network with tiny relays

A small low-power relay can add another hop where it is useful: in a hallway, near a window, inside a vehicle, at an event venue, or around a community space.

The first embedded relay implementation targets the XIAO ESP32-C6.

---

## Features

| Feature                    | Description                                                        |
| -------------------------- | ------------------------------------------------------------------ |
| **Nearby chat**            | Exchange messages directly with nearby devices over BLE.           |
| **Multi-hop mesh routing** | Forward messages through phones and relay nodes.                   |
| **Direct messages**        | Chat privately with individual peers.                              |
| **Channels**               | Join shared conversations for groups and communities.              |
| **MeshCore integration**   | Reach a MeshCore LoRa network through nearby gateways.             |
| **Nearby nodes**           | See which devices are currently visible around you.                |
| **Network topology**       | Explore how local nodes are connected.                             |
| **Packet traces**          | Inspect the route taken by a packet through the network.           |
| **Embedded relays**        | Extend coverage with inexpensive low-power hardware.               |
| **Local-first operation**  | Keep communicating without a cloud service or internet connection. |

---

## Download

Meshward is currently available as an experimental Android APK.

<p align="center">
  <a href="https://github.com/meshcore-cz/meshward/releases/latest">
    <img src="https://img.shields.io/badge/Download-latest%20APK-5865f2?style=for-the-badge&logo=android" alt="Download latest APK">
  </a>
</p>

<p align="center">
  <a href="https://github.com/meshcore-cz/meshward/releases">Browse all releases</a>
  ·
  <a href="https://github.com/meshcore-cz/meshward/issues">Report an issue</a>
</p>

> [!WARNING]
> Meshward is an early experimental preview. Expect breaking changes, incomplete features, and hardware-specific limitations.

### Install on Android

1. Download the latest `.apk` file from the releases page.
2. Open the downloaded file on your Android device.
3. Allow installation from your browser or file manager when Android asks for permission.
4. Install and launch Meshward.

---

## Where can it be useful?

Meshward is designed for situations where nearby communication matters more than permanent internet connectivity.

* local communities and neighbourhoods
* hackerspaces and community hubs
* festivals, conferences, and meetups
* travel, camping, and outdoor activities
* internet and mobile-network outages
* MeshCore deployments
* experimental off-grid networks

A useful network can start with only two phones:

```text
phone  ↔  phone
```

Add a relay when you need another hop:

```text
phone  ↔  relay  ↔  phone
```

Add a gateway when you want to reach MeshCore:

```text
phone  ↔  relay  ↔  gateway  ↔  MeshCore
```

---

## Powered by Sidepath

Meshward uses the [Sidepath Protocol](https://github.com/meshcore-cz/sidepath-protocol) for nearby-device communication.

Sidepath is the lower-level BLE mesh protocol responsible for peer discovery, routing, packet relaying, deduplication, topology announcements, and diagnostics.

Meshward turns Sidepath into a messenger designed for everyday use.

```text
Meshward app
└── Sidepath nearby mesh
    ├── phone-to-phone communication
    ├── multi-hop relay nodes
    └── optional MeshCore gateways
```

---

## Build from source

Development documentation will live in [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md).

```bash
git clone https://github.com/meshcore-cz/meshward.git
cd meshward
./gradlew assembleDebug
```

The APK will be created at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

---

## Related projects

* [Sidepath Protocol](https://github.com/meshcore-cz/sidepath-protocol) — nearby-device BLE mesh protocol
* [MeshCore](https://github.com/meshcore-dev/MeshCore) — long-range LoRa mesh networking firmware
* [meshpkt](https://github.com/meshcore-cz/meshpkt) — MeshCore packet codec

---

<p align="center">
  <strong>Meshward carries the mesh further.</strong>
</p>
