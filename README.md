# Win Monitor

Win Monitor is a lightweight native Windows desktop application for real-time CPU, GPU, and RAM monitoring. It is designed as a single portable executable with no runtime service, installer, or external configuration required.

## Features

- Live CPU utilization sampled every second.
- Live system RAM usage with used/total memory figures.
- GPU utilization with NVIDIA NVML support when an NVIDIA driver exposes `nvml.dll`.
- Vendor-neutral Windows Performance Data Helper (PDH) fallback for GPU engine telemetry.
- Dedicated GPU memory reporting when NVIDIA NVML is available.
- 60-second rolling graphs for CPU, GPU, and RAM.
- Compact native Win32 user interface using standard Windows APIs.
- Portable x64 executable; no installer or framework runtime is required.
- MIT licensed.

## Requirements

- Windows 10 or Windows 11.
- 64-bit Intel or AMD processor.
- For direct NVIDIA utilization and VRAM telemetry, an installed NVIDIA driver exposing `nvml.dll` is required. Other GPUs use the Windows GPU Engine performance-counter fallback where available.

## Download

The GitHub Releases page publishes the Windows x64 executable as:

`win-monitor-windows-x64.exe`

## Building from source

Install Go 1.23 or newer.

```powershell
go test ./...
$env:GOOS = "windows"
$env:GOARCH = "amd64"
$env:CGO_ENABLED = "0"
go build -trimpath -ldflags "-H=windowsgui -s -w" -o dist/win-monitor-windows-x64.exe ./cmd/win-monitor
```

The same build can be produced from a non-Windows host using the environment variables above.

## Usage

Run `win-monitor-windows-x64.exe`. The application begins sampling immediately and refreshes once per second.

Press `Esc` to close the application.

## Telemetry details

### CPU

CPU utilization is derived from the delta between Windows system idle, kernel, and user times. The value represents aggregate logical-processor utilization for the system.

### RAM

Memory usage is read with `GlobalMemoryStatusEx` and is expressed as a percentage of physical memory, with the dashboard also showing used and total memory in decimal gigabytes.

### GPU

When NVIDIA's Management Library is available, Win Monitor reads the first NVIDIA adapter's utilization and memory counters directly through dynamically loaded NVML functions. No NVIDIA SDK is bundled with the application.

When NVML is unavailable, the application falls back to Windows GPU performance counters. Counter availability and semantics can vary by Windows version, graphics driver, and hardware vendor; unavailable GPU metrics are shown as zero or a generic fallback label rather than blocking the rest of the dashboard.

## Architecture

The application is intentionally dependency-light:

- `cmd/win-monitor` contains the Windows application entry point and Win32 rendering/event loop.
- `internal/ui` contains the rolling-history data structure and portable unit tests.
- Windows APIs are loaded dynamically through Go's standard `syscall` package, so the release does not depend on external DLLs beyond Windows system libraries and optionally the NVIDIA driver.

## Release workflow

Pushing a version tag such as `v0.1` triggers the GitHub Actions workflow in `.github/workflows/release.yml`. The workflow:

1. Runs the test suite.
2. Cross-compiles the application for Windows x64.
3. Packages the executable with the exact release asset name `win-monitor-windows-x64.exe`.
4. Creates a GitHub Release for the tag and uploads the executable.

## License

Win Monitor is released under the MIT License. See [LICENSE](LICENSE) for the complete text.

## Disclaimer

Win Monitor is a monitoring utility, not a hardware diagnostic or stability-testing tool. Telemetry depends on Windows and graphics-driver support. Values can differ from those reported by vendor-specific control panels or other monitoring software.
