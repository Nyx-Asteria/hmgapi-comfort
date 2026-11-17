# Comfort & Startup (`hmgapi-comfort`)

A **Material Design 3** cabin comfort & startup automation mod for **Hyundai, Kia, and Genesis Gen5W** widescreen head units running **[HMG-API](https://github.com/Open-HMG/HMG-API)** (`>= 1.0.0`).

Automatically dismisses the infotainment startup confirmation screen across cold boots and QuickBoot wakes, warms your seats and steering wheel across 3 customizable winter temperature tiers, ventilates front seats across 3 summer cooling tiers, and gives you a native two-pane **Material Design 3** dashboard right on your car's `1920×720` display.

> **Looking for the full one-tap US Ioniq 5 bundle?** Comfort & Startup is also included in **[`gen5w-suite`](https://github.com/lemoyneiv/gen5w-suite)**. This repository provides **standalone packages** for the **NEMESIS Package Store**, NEMESIS USB, and the HMG-API app on any Gen5W vehicle.

---

## Features

- **Instant Startup Confirmation Dismissal (`WelcomeDaemon`)**
  - Lightweight boot-stage daemon (`start: "boot"`) that dismisses the infotainment consent notice within milliseconds of `sys.boot_completed=1` on cold boot and within `150 ms` on QuickBoot suspend-to-RAM resume—only while safely in Park (`P`).
  - Configurable **Dismiss Delay (`0–2500 ms`)** if you prefer a brief pause after wake.
- **3-Tier Cold-Weather Seat & Steering Wheel Warming**
  - Independent outside-temperature thresholds for **Level 3 (High)**, **Level 2 (Medium)**, and **Level 1 (Low)** seat heat (defaults: `≤ 25 °F` → L3, `≤ 40 °F` → L2, `≤ 55 °F` → L1), plus an independent **Steering Wheel Heat** threshold (`≤ 48 °F`).
  - Ignores raw `-40 °F` pre-CAN wake sentinels and **never overrides** manual seat/wheel changes you've already made on the current drive.
- **3-Tier Warm-Weather Front Seat Ventilation**
  - Automatic summer seat cooling thresholds for **Level 3**, **Level 2**, and **Level 1** front ventilation (defaults: `≥ 90 °F` → L3, `≥ 82 °F` → L2, `≥ 75 °F` → L1) on vehicles equipped with ventilated seats.
- **Passenger & Rear Heated Seat Policies**
  - **Front Passenger Policy** (`Off`, `-1 Step` gentler than driver, or `Match` driver) for both heating and cooling.
  - **Rear Heated Seats Policy** (`Off`, `-1 Step`, or `Match` up to 2 rear levels) for vehicles with rear seat heaters.
- **Material Design 3 Widescreen Dashboard (`1920×720`)**
  - Adaptive **Day / Night** M3 tonal surfaces (`UiModeManager`), animated stadium switches, segmented controls, live 4-seat cabin preview tiles, a `-5 °F .. 115 °F` seasonal comfort spectrum bar with live outside temperature pin, one-tap **Apply Now** test button, **Star (★) Key** binding support, and stock-gradient Gen5W launcher tiles.
  - Zero `/system` file modifications and zero firmware-specific hook points—removing or disabling the mod leaves the head unit 100% stock.

---

## Requirements

- A **Hyundai, Kia, or Genesis** vehicle with a **Gen5W** navigation head unit.
- **[HMG-API](https://github.com/Open-HMG/HMG-API)** `1.0.0` or newer installed ([Installing HMG-API](https://github.com/Open-HMG/HMG-API/wiki/Installing-HMG-API)).
- **NEMESIS** (`>= 1.16`) if installing from the **NEMESIS Package Store** or a NEMESIS USB stick.

---

## Installation

Install **[HMG-API](https://github.com/Open-HMG/HMG-API/wiki/Installing-HMG-API)** first, then install Comfort & Startup using whichever method you prefer:

| Release Asset | Use For |
|---|---|
| **`hmgapi-comfort-v<version>-nemesis-package.zip`** | **NEMESIS Package Store** (`hmgapi.hmgapi-comfort`) or `nemesis install` *(do not unzip onto a flash drive)* |
| **`hmgapi-comfort-v<version>-nemesis-usb.zip`** | Installing via **NEMESIS** from a FAT32 / exFAT USB flash drive |
| **`hmgapi-comfort-v<version>-hmgapi-usb.zip`** | Installing via the **HMG-API** app (**Updates** tab) from a FAT32 / exFAT USB flash drive |
| **`hmgapi-comfort-v<version>.zip`** | Signed HMG-API mod package on its own (`hmgapid engine install`) |

### Option 1 — NEMESIS Package Store
Search for **Comfort & Startup** (`hmgapi.hmgapi-comfort`) in the **NEMESIS Package Store** and tap **Install**. Tapping **Launch** in NEMESIS Manager opens the **Comfort** Material 3 dashboard directly.

### Option 2 — With NEMESIS (USB Flash Drive)
1. Unzip **`hmgapi-comfort-v<version>-nemesis-usb.zip`** to the root of a **FAT32** or **exFAT** USB drive so `NEMESIS.data` and `packages/` are at the top level (keep only one NEMESIS USB package on the stick).
2. With Android booted on the head unit, plug the USB drive into the car's data USB port and accept the **Comfort & Startup** install prompt.
3. Remove the USB drive once complete. *(Note: automatically refuses to install alongside legacy `coldstart.heat` or `local.hkmc.factory82` packages so two mods never fight over seat heat or startup notice dismissal—uninstall those first if prompted.)*

### Option 3 — With the HMG-API App (USB Flash Drive)
1. Unzip **`hmgapi-comfort-v<version>-hmgapi-usb.zip`** to the root of a **FAT32** or **exFAT** USB drive so the drive contains `HMGAPI/updates/hmgapi-comfort-v<version>.zip`.
2. Plug the drive into the car, open the **HMG-API** app → **Updates**, install **Comfort & Startup**, and tap **Apply changes**.

---

## Required First-Run Permissions in HMG-API

HMG-API protects vehicle climate and startup commands behind owner switches. After installing **Comfort & Startup**, open the **HMG-API** app → **Settings / Permissions** once and turn on:

1. **Dismiss the startup notice** (global owner switch + **Comfort & Startup** `startup_notice`)
2. **Seat heat** (`seat_heat` for **Comfort & Startup**)
3. **Seat ventilation** (`seat_vent` for **Comfort & Startup**, if your car has ventilated seats)
4. **Steering wheel heat** (`wheel_heat` for **Comfort & Startup**, if your car has a heated wheel)

Then open **Comfort** from the head unit's **All Apps** screen (or **NEMESIS Manager → Launch**, or bind **Comfort & Startup** to your steering-wheel/dash **★** button) and tap **Apply Now** to test immediately.

---

## Compatibility

Comfort & Startup uses HMG-API's cross-vehicle `vehicle` service (`outside_temp_f`, `hv.ready`, `gear.park`, `seat_heat.*`, `seat_vent.*`, `wheel_heat`, `startup_notice`) and requires **no per-firmware bytecode hooks**.

| Vehicle | Firmware Tested | HMG-API |
|---|---|---|
| Hyundai Ioniq 5 (`NEEV`, `NEEV23`, `NEEV23SE`, US) | `260507`, `260903` | `1.1.0-rc.3` |

Tried it on another Hyundai, Kia, or Genesis Gen5W vehicle? Open a [Car Compatibility Report](../../issues/new?template=car-report.yml) to add your model to the table!

---

## Building from Source

```sh
./build.sh
```

`build.sh` automatically downloads and SHA-256-verifies the official **[Open-HMG/HMG-API](https://github.com/Open-HMG/HMG-API)** Mod SDK pinned in [`hmgapi-sdk.json`](hmgapi-sdk.json), compiles `Comfort.apk` targeting Android 4.4.2 (`API 19`), runs all host policy unit tests (`hmgapi-comfort/tools/unit/test.sh`), package tests (`hmgapi-comfort/test.sh`), and head-unit `mksh` script checks (`check-scripts.sh`), and writes signed HMG-API + NEMESIS packages into `dist/`.

Official releases are signed with author key **`28ad71e0-07c0-8e61-8bf3-380cc71f9280`** (`lemoyneiv`).

---

## License & Disclaimer

Licensed under **[GNU AGPL-3.0](LICENSE)**. Built on **[Open-HMG/HMG-API](https://github.com/Open-HMG/HMG-API)**.

Not affiliated with or endorsed by Hyundai, Kia, or Genesis. Modifying your vehicle's infotainment head unit is at your own risk.
