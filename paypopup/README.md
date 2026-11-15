# Pay Popup (Fabric, Minecraft 26.3)

A client-side mod. When someone `/pay`s you on a server, an animated popup drops down from the top of the screen:

- Slides in with a bounce, and the amount "pops"
- Gold coins burst out of it
- The border is green under $1M, gold at $1M+, and **rainbow** at $1B+
- Plays a level-up sound (and a bigger sound for $1B+)
- Payments that arrive at the same time queue up and show one after another

## Commands
| Command | What it does |
|---|---|
| `/paypopup test` | Shows a fake $2.5M popup |
| `/paypopup test 5000000000` | Fake popup for any amount |
| `/paypopup toggle` | Turns the mod on/off |
| `/paypopup min 1000000` | Only pop up for $1M or more |

Other settings are in `.minecraft/config/paypopup.json`.

## How to build the .jar
1. Install **Java 25 (JDK)**, for example Temurin 25 from adoptium.net.
2. Unzip this folder, open a terminal inside it, and run:
   - Windows: `gradlew.bat build`
   - Mac/Linux: `./gradlew build`
3. The mod will be at `build/libs/paypopup-1.0.0.jar`. Use the one **without** `-sources`.
4. Put it in `.minecraft/mods` along with **Fabric API** for 26.3, then launch with Fabric Loader 26.3.

If the build fails on a version number, check https://fabricmc.net/develop and update `gradle.properties`.
