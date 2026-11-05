# Elder Launcher

A very simple Android home screen for an older relative: big photo buttons that call family over WhatsApp, a huge "Answer" screen for incoming calls, and a phone that stays loud and bright without anyone having to touch the settings.

It is managed by the family, not by the person using it. Nothing on the phone can be changed by accident. **It works on its own, with no server.** If you want to manage it from afar, you can optionally connect it to a [control plane](#control-plane) that you host yourself.

<p align="center">
  <img src="docs/screenshots/home.png" width="23%" alt="Home screen with four large photo buttons, the time and the battery level">
  <img src="docs/screenshots/call.png" width="23%" alt="Incoming call screen with the caller's photo, a huge Answer button and a Hang up button">
  <img src="docs/screenshots/message.png" width="23%" alt="Full-screen message from a relative, with OK and Call buttons">
  <img src="docs/screenshots/looking.png" width="23%" alt="Trying to reach you screen with a large Call button">
</p>
<p align="center"><sub>Home, incoming call, message read aloud and "Trying to reach you". Screenshots from an Android 11 emulator, with made-up contacts.</sub></p>

> **Status: 2.0.0.** This is the continuation of a launcher in daily use on a Xiaomi phone (MIUI 12.5, Android 11). This release has been tested end to end on an Android 11 emulator, including the installer and a control plane. Real WhatsApp calls need a real phone, and other brands are untested. Reports are welcome.

## What it does

- **Photo buttons.** Tap twice to call a contact over WhatsApp. The first tap highlights the button and asks for confirmation, so a stray touch does nothing.
- **Incoming calls.** WhatsApp and phone calls open a full-screen page with the caller's photo, a huge "Answer" (one tap) and "Hang up" (two taps). It shows on a locked phone.
- **Always loud.** Ringer, notifications and call voice go back to maximum if lowered, and silent and Do Not Disturb modes are turned off.
- **Media volume.** While watching YouTube, volume returns to a cap (50 % by default). Elsewhere it stays at maximum.
- **Always bright.** Brightness returns to maximum a few seconds after being lowered, with an optional dimmer level at night.
- **Remote commands (optional).** Play a video full screen above the lock screen, show a full-screen message read aloud, or show "Trying to reach you" with a one-tap call back button.
- **Power menu guard (optional, experimental).** Closes the power-off, restart and airplane mode menu when the side button opens it by accident.

## Two ways to run it

You choose when installing, and you can change your mind later by running the installer again.

| | Standalone (default) | With a control plane |
| --- | --- | --- |
| **Where it runs** | Only on the phone | The app on the phone, plus a web service on **a separate server you host** |
| **What you need** | A computer with `adb` for the install | The same, plus an always-on server with HTTPS |
| **Contact buttons and photos** | `contacts.json` and `photos/`, copied by the installer | Managed on the server and downloaded by the phone |
| **Changing settings** | Over ADB, or with [broadcasts](#remote-commands) | On the server, or as in standalone |
| **Data sent off the phone** | None | [Installed apps and call status](#safety-and-privacy) go to your server |

```mermaid
flowchart LR
  subgraph standalone["Standalone (default)"]
    A["Your computer"] -- "install.sh over ADB<br/>once, or to make changes" --> B["Phone"]
  end
  subgraph managed["With a control plane (optional)"]
    C["Family, in a browser"] --> D["Your server<br/>control plane"]
    E["Phone"] -- "HTTPS: the phone asks,<br/>the server only answers" --> D
  end
```

Calls, the answer screen, volume and brightness work the same in both modes, and they keep working if the server goes down.

## Requirements

- A phone with Android 11 or newer, with WhatsApp installed and signed in
- A computer with [`adb`](https://developer.android.com/tools/releases/platform-tools)
- On the phone: Developer options > USB debugging, enabled and authorized
- On Xiaomi phones, also: "Install via USB" and "USB debugging (Security settings)"
- Only for the optional [control plane](#control-plane): your own server

## Install

```sh
git clone https://github.com/cfpandrade/elder-launcher.git
cd elder-launcher

cp contacts.example.json contacts.json   # then edit it with your family
./install.sh --config contacts.json --photos photos/
```

The installer downloads the latest release, verifies its checksum and installs it. It then grants the permissions, sets the app as the home screen and copies your configuration to the phone. Run it again at any time to update. `contacts.json` and `photos/` are git-ignored, so your family's data stays out of your fork.

When run in a terminal, it asks whether to use a control plane, and the default is no server, or the server already configured on a phone that has one. To skip the question, or in a script, use one of these:

```sh
./install.sh --config contacts.json --photos photos/ --no-server            # standalone
./install.sh --server https://launcher.example.com --token-file token.txt   # with your control plane
```

Without a terminal and without these options, the installer leaves the phone's current choice as it is.

| Option | What it does |
| --- | --- |
| `-s`, `--serial SERIAL` | Target device, when several are connected |
| `--apk FILE` | Install this APK instead of `build/elder-launcher.apk` or the latest release |
| `--config FILE` | `contacts.json` to copy to the phone |
| `--photos DIR` | Folder with the photos named in `contacts.json` |
| `--no-server` | Standalone: no server, and forget one configured before |
| `--server URL` | Use the [control plane](#control-plane) at this `https://` address |
| `--token-file FILE` | File with the token for `--server`. Without it, the installer asks |
| `--remote FILE` | Use an existing `remote.json`, instead of `--server` |
| `--set KEY=VALUE` | Change a [setting](#settings); repeatable |
| `--block-power-menu` | Turn on the power menu guard |
| `--keep-debugging` | Keep wireless debugging on. With a control plane, also report its port to it |
| `--no-home` | Do not set it as the default home app |
| `--uninstall` | Remove the app |
| `--dry-run` | Print the commands that would change the phone, without running them |

If the installer cannot set the home app, press Home on the phone and choose Elder Launcher with "Always". Also check that the phone's battery saver is not restricting the app, since a killed app cannot show incoming calls.

## Contacts

```json
{
  "contacts": [
    { "name": "Alice", "lookup": "Alice Smith", "phone": "+12025550101", "photo": "alice.jpg" },
    { "name": "Carol", "lookup": "Carol Jones" }
  ],
  "youtube": true,
  "more": ["com.android.camera", "com.android.contacts"]
}
```

| Field | Meaning |
| --- | --- |
| `name` | The label on the button |
| `lookup` | The contact's name in the address book; defaults to `name` |
| `phone` | Optional, in international format. It wins over `lookup`, and **the number is added to the address book if missing**, because WhatsApp needs that to call it |
| `photo` | Optional file in the photos folder. Without it, the address book photo; without that, the initial |
| `youtube` | Show the YouTube button |
| `more` | Package names for the "More" page, in order. Without it: camera, contacts and phone |

A newly added contact can take a while to sync with WhatsApp. Until then, its button opens the chat.

## Settings

Change them with the installer or directly over ADB:

```sh
./install.sh --set night_brightness=30 --set night_from=21:30

adb shell am broadcast \
  -n io.github.cfpandrade.elderlauncher/.SettingsReceiver \
  -a io.github.cfpandrade.elderlauncher.SETTING \
  --es brightness_max false
```

| Key | Default | Meaning |
| --- | --- | --- |
| `brightness_max` | `true` | Brightness returns to maximum. Turns adaptive brightness off |
| `night_brightness` | `0` | Brightness at night, % of maximum. `0` means the same as daytime |
| `night_from`, `night_to` | `22:00`, `07:00` | When night starts and ends |
| `screen_timeout` | `0` | Seconds until the screen turns off. `0` leaves it alone |
| `volume_youtube` | `50` | Media volume cap while watching YouTube, in % |
| `volume_outside` | `true` | Outside YouTube, media volume at maximum |
| `auto_answer` | `false` | Answer calls from configured contacts after 20 seconds, on speaker |
| `block_power_menu` | `false` | Power menu guard |
| `keep_wireless_debugging` | `false` | Keep wireless debugging on and report its port |

## Remote commands

These are broadcasts, so ADB, Home Assistant's companion app or any app on the phone can send them. They do not need a control plane, and a control plane cannot send them: the phone only ever calls the server, never the other way round. All of them use the action prefix `io.github.cfpandrade.elderlauncher.`.

| Action | Extras | Effect |
| --- | --- | --- |
| `VIDEO` | `id`, `volume` | Plays a YouTube video full screen, above the lock screen |
| `STOP` | `who` (optional) | Stops what is playing and goes home. With `who`, shows "Trying to reach you" with a call button |
| `MEDIA` | `command`, `volume` | `pause`, `resume`, `stop`, or `lower`. `lower` is honored for 10 minutes outside YouTube |
| `MESSAGE` | `text`, `who` | Shows a full-screen message and reads it aloud, for 10 minutes at most |
| `SETTING` | any [setting](#settings) | Changes settings |

From Home Assistant, with a `notify.mobile_app_*` service:

```yaml
service: notify.mobile_app_grandmas_phone
data:
  message: command_broadcast_intent
  data:
    intent_package_name: io.github.cfpandrade.elderlauncher
    intent_action: io.github.cfpandrade.elderlauncher.MESSAGE
    intent_extras: "text:Lunch%20is%20at%20one:String.urlencoded,who:Alice:String.urlencoded"
```

## Control plane

The control plane is **optional**. It is a web service that lets the family manage the phone's buttons, photos and settings from afar.

- **It runs on a server, not on the phone.** You need a separate always-on machine, such as a home server or a VPS, that the phone can reach wherever it is used.
- **It is not included.** This repository contains only the app and its installer. To use a control plane, you implement the small API below on your own server.
- **It must use HTTPS with a trusted certificate.** Android blocks unencrypted traffic for this app, and it does not trust self-signed certificates. A reverse proxy in front of your service is enough.

To connect the phone, choose "Use my server" in the installer, or pass the address and the token. The token is a long random secret that your server also knows:

```sh
./install.sh --server https://launcher.example.com --token-file token.txt
```

The installer stores it as `remote.json` in the app's private storage. You can also write that file yourself and pass it with `--remote`:

```json
{ "url": "https://launcher.example.com", "token": "a-long-random-secret" }
```

To go back to standalone, run `./install.sh --no-server`. The buttons then come from `contacts.json` again, so also pass `--config` if the phone does not have one. Settings received from the server stay as they are until you change them.

### API

The phone checks at most every 10 minutes when returning to the home screen, and it keeps working from the last download when offline. While connected, the server's buttons replace those in `contacts.json`. Every request carries the `X-Launcher-Token` header, and any answer other than HTTP 200 counts as a failure.

| Request | Body or response |
| --- | --- |
| `GET /api/launcher` | Response below |
| `GET /api/launcher/photo/{id}` | The button's JPEG. With `?type=full`, the whole photo for the call screen |
| `POST /api/launcher/apps` | `{"hash": "...", "apps": [{"package", "name", "icon"}]}`, where `icon` is a base64 PNG |
| `POST /api/launcher/call` | `{"state": "idle\|ringing\|in_call", "with": "...", "since": 1700000000000, "app": "whatsapp\|phone"}` |
| `POST /api/launcher/adb` | `{"ip": "...", "port": 12345}`, only with `keep_wireless_debugging` |

```json
{
  "ok": true,
  "buttons": [
    { "id": "a1b2c3d4", "name": "Alice", "lookup": "Alice Smith", "phone": "+12025550101",
      "photo": "0123456789abcdef", "full_photo": "fedcba9876543210" }
  ],
  "more": ["com.android.camera"],
  "apps_hash": "...",
  "brightness_max": true,
  "volume_youtube": 50
}
```

`photo` and `full_photo` are 16 lowercase hexadecimal characters that identify each image, and a new value makes the phone download it again. Any setting can be included at the top level.

## Build from source

You only need Docker. The SDK runs in a container, without Gradle.

```sh
./build.sh                # creates build/elder-launcher.apk
./install.sh --config contacts.json
```

The first build creates a signing key in `signing/` with a random password. Keep that folder safe, because Android only accepts updates signed with the same key. A phone with the release build needs an uninstall before it can take your own build.

To translate the interface, add `res/values-xx/strings.xml`. Android chooses the language from the phone, and the date and text-to-speech voice follow it too. Button labels in other apps and in the system are matched by text, so for a new language also extend the patterns in `CallWatcherService.java` and `PowerMenuGuard.java`. They cover several languages today, which is why you will find some non-English words there.

## Safety and privacy

- **Consent.** This app takes control of a phone: it reads notifications, overrides volume and brightness, and can answer calls. Install it only with the agreement of the person who uses the phone, or of whoever legally looks after them.
- **Open commands.** Broadcast receivers are exported so that other apps can send commands, which means any app on the phone can too. Install only apps you trust on it.
- **Power menu guard.** It enables an accessibility service. Recognizing the menu depends on its labels and window class, so verify it on your phone: the app logs `power menu: closed` when it works. Holding the side button for several seconds still forces a restart. To restart normally, first send `block_power_menu=false`.
- **Wireless debugging.** `keep_wireless_debugging` keeps a remote access door open. It still requires a computer that was paired beforehand, but enable it only on a network you trust.
- **Data.** In standalone mode the app sends nothing anywhere. With a control plane, your server receives the installed apps, who the phone is on a call with and, if enabled, the debugging port. The token stays in the app's private storage. Only ADB can replace it, and nothing can read it back.

## Troubleshooting

```sh
adb logcat -s ElderLauncher:*
```

- **Incoming calls do not open the big screen.** Check that notification access is on and that battery saver is not restricting the app.
- **Brightness does not come back.** `screen: missing WRITE_SETTINGS` in the log means the grant failed. On Xiaomi phones, enable "USB debugging (Security settings)" and run the installer again.
- **A button opens the chat instead of calling.** WhatsApp has not synced that contact yet, or the contact has no WhatsApp.
- **A video does not play in the built-in screen.** It falls back to the YouTube app, which asks to unlock the phone.

## License

[MIT](LICENSE)
