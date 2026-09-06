#!/usr/bin/env python3
'''
Build a ready-to-use FastMinecarts plugin for Paper 26.2.

What it does:
- Generates a small Maven Java plugin project.
- Downloads Maven automatically if "mvn" is not installed.
- Compiles against Paper API 26.2 build 119.
- Produces FastMinecarts.jar, ready to place in plugins/.

Requirements:
- Python 3.9+
- JDK 25+ available as "java" and "javac"
- Internet access during the build
'''

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import urllib.request
import zipfile
from pathlib import Path


PLUGIN_NAME = "FastMinecarts"
PLUGIN_VERSION = "1.0.0"
PAPER_API_VERSION = "26.2.build.119-stable"
MAVEN_VERSION = "3.9.16"

MAVEN_URLS = [
    f"https://dlcdn.apache.org/maven/maven-3/{MAVEN_VERSION}/binaries/apache-maven-{MAVEN_VERSION}-bin.zip",
    f"https://archive.apache.org/dist/maven/maven-3/{MAVEN_VERSION}/binaries/apache-maven-{MAVEN_VERSION}-bin.zip",
]


JAVA_SOURCE = r'''package local.fastminecarts;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;

public final class FastMinecartsPlugin extends JavaPlugin implements Listener {

    private static final double TICKS_PER_SECOND = 20.0;
    private static final double MAX_ALLOWED_BPS = 1000.0;

    private double speedBlocksPerSecond;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();

        Bukkit.getPluginManager().registerEvents(this, this);

        registerCommand("minecartspeed", new BasicCommand() {
            @Override
            public void execute(CommandSourceStack source, String[] args) {
                handleCommand(source.getSender(), args);
            }

            @Override
            public String permission() {
                return "fastminecarts.admin";
            }
        });

        int changed = applyToAllLoadedMinecarts();

        getLogger().info(String.format(
                Locale.ROOT,
                "Enabled. Minecart max speed: %.2f blocks/sec (%.4f blocks/tick). Updated %d loaded minecart(s).",
                speedBlocksPerSecond,
                speedBlocksPerSecond / TICKS_PER_SECOND,
                changed
        ));
    }

    private void loadSettings() {
        speedBlocksPerSecond = getConfig().getDouble("speed-blocks-per-second", 16.0);

        if (!Double.isFinite(speedBlocksPerSecond) || speedBlocksPerSecond < 0.0) {
            getLogger().warning("Invalid speed-blocks-per-second in config.yml; using 16.0.");
            speedBlocksPerSecond = 16.0;
        }

        if (speedBlocksPerSecond > MAX_ALLOWED_BPS) {
            getLogger().warning("Speed is above 1000 blocks/sec; clamping to 1000.");
            speedBlocksPerSecond = MAX_ALLOWED_BPS;
        }
    }

    @EventHandler
    public void onEntityAddedToWorld(EntityAddToWorldEvent event) {
        if (event.getEntity() instanceof Minecart minecart) {
            applySpeed(minecart);
        }
    }

    private void applySpeed(Minecart minecart) {
        // Bukkit/Paper Minecart#setMaxSpeed uses blocks per tick.
        minecart.setMaxSpeed(speedBlocksPerSecond / TICKS_PER_SECOND);
    }

    private int applyToAllLoadedMinecarts() {
        int count = 0;

        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Minecart minecart) {
                    applySpeed(minecart);
                    count++;
                }
            }
        }

        return count;
    }

    private void handleCommand(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(String.format(
                    Locale.ROOT,
                    "Minecart max speed is %.2f blocks/sec. Usage: /minecartspeed <speed|reload>",
                    speedBlocksPerSecond
            ));
            return;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            loadSettings();
            int changed = applyToAllLoadedMinecarts();

            sender.sendMessage(String.format(
                    Locale.ROOT,
                    "FastMinecarts reloaded: %.2f blocks/sec. Updated %d loaded minecart(s).",
                    speedBlocksPerSecond,
                    changed
            ));
            return;
        }

        final double requestedSpeed;
        try {
            requestedSpeed = Double.parseDouble(args[0]);
        } catch (NumberFormatException ex) {
            sender.sendMessage("Speed must be a number, for example: /minecartspeed 16");
            return;
        }

        if (!Double.isFinite(requestedSpeed) || requestedSpeed < 0.0 || requestedSpeed > MAX_ALLOWED_BPS) {
            sender.sendMessage("Speed must be between 0 and 1000 blocks/sec.");
            return;
        }

        speedBlocksPerSecond = requestedSpeed;
        getConfig().set("speed-blocks-per-second", speedBlocksPerSecond);
        saveConfig();

        int changed = applyToAllLoadedMinecarts();

        sender.sendMessage(String.format(
                Locale.ROOT,
                "Minecart max speed set to %.2f blocks/sec. Updated %d loaded minecart(s).",
                speedBlocksPerSecond,
                changed
        ));
    }
}
'''

PLUGIN_YML = r'''name: FastMinecarts
version: 1.0.0
main: local.fastminecarts.FastMinecartsPlugin
description: Raises the maximum speed of minecarts without enabling Minecart Improvements.
api-version: '26.2'

permissions:
  fastminecarts.admin:
    description: Allows changing the FastMinecarts speed.
    default: op
'''

CONFIG_YML_TEMPLATE = '''# Maximum minecart speed in blocks per second.
# Vanilla/default minecart cap is approximately 8 blocks/sec.
speed-blocks-per-second: {speed}
'''

POM_XML = f'''<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>local.fastminecarts</groupId>
    <artifactId>fastminecarts</artifactId>
    <version>{PLUGIN_VERSION}</version>

    <properties>
        <maven.compiler.release>25</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <repositories>
        <repository>
            <id>papermc</id>
            <url>https://repo.papermc.io/repository/maven-public/</url>
        </repository>
    </repositories>

    <dependencies>
        <dependency>
            <groupId>io.papermc.paper</groupId>
            <artifactId>paper-api</artifactId>
            <version>{PAPER_API_VERSION}</version>
            <scope>provided</scope>
        </dependency>
    </dependencies>

    <build>
        <finalName>FastMinecarts</finalName>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.14.1</version>
            </plugin>
        </plugins>
    </build>
</project>
'''


def run(cmd: list[str], cwd: Path | None = None) -> None:
    print("+", " ".join(str(x) for x in cmd))
    subprocess.run(cmd, cwd=cwd, check=True)


def detect_java_major() -> int:
    try:
        result = subprocess.run(
            ["java", "-version"],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            check=True,
        )
    except (FileNotFoundError, subprocess.CalledProcessError):
        raise RuntimeError(
            'Java was not found. Install a JDK 25+ and make sure "java" and "javac" are on PATH.'
        )

    first_line = result.stdout.splitlines()[0] if result.stdout else ""
    match = re.search(r'version\s+"(\d+)', first_line)
    if not match:
        match = re.search(r'openjdk\s+(\d+)', first_line, re.IGNORECASE)

    if not match:
        raise RuntimeError(f"Could not determine Java version from: {first_line}")

    major = int(match.group(1))

    if shutil.which("javac") is None:
        raise RuntimeError(
            '"javac" was not found. You need a JDK, not only a Java runtime.'
        )

    return major


def download(url: str, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    print(f"Downloading {url}")

    request = urllib.request.Request(
        url,
        headers={"User-Agent": "FastMinecarts-Builder/1.0"},
    )

    with urllib.request.urlopen(request) as response, destination.open("wb") as out:
        total = response.headers.get("Content-Length")
        total_size = int(total) if total and total.isdigit() else None
        downloaded = 0
        chunk_size = 1024 * 1024

        while True:
            chunk = response.read(chunk_size)
            if not chunk:
                break

            out.write(chunk)
            downloaded += len(chunk)

            if total_size:
                pct = downloaded * 100.0 / total_size
                print(
                    f"\r  {pct:5.1f}% ({downloaded // 1048576} MiB)",
                    end="",
                    flush=True,
                )

        if total_size:
            print()


def obtain_maven(cache_dir: Path) -> Path:
    installed = shutil.which("mvn")
    if installed:
        print(f"Using installed Maven: {installed}")
        return Path(installed)

    archive = cache_dir / f"apache-maven-{MAVEN_VERSION}-bin.zip"
    extracted = cache_dir / f"apache-maven-{MAVEN_VERSION}"
    executable = extracted / "bin" / ("mvn.cmd" if os.name == "nt" else "mvn")

    if executable.exists():
        print(f"Using cached Maven: {executable}")
        return executable

    last_error = None

    for url in MAVEN_URLS:
        try:
            if archive.exists():
                archive.unlink()

            download(url, archive)
            last_error = None
            break
        except Exception as exc:
            last_error = exc
            print(f"Download failed: {exc}")

    if last_error is not None:
        raise RuntimeError(
            "Could not download Maven from any configured mirror."
        ) from last_error

    print(f"Extracting Maven {MAVEN_VERSION}...")
    cache_dir.mkdir(parents=True, exist_ok=True)

    with zipfile.ZipFile(archive, "r") as zf:
        zf.extractall(cache_dir)

    if not executable.exists():
        raise RuntimeError(
            f"Maven executable was not found after extraction: {executable}"
        )

    if os.name != "nt":
        executable.chmod(executable.stat().st_mode | 0o111)

    return executable


def write_project(project_dir: Path, initial_speed: float) -> None:
    java_dir = (
        project_dir
        / "src"
        / "main"
        / "java"
        / "local"
        / "fastminecarts"
    )
    resources_dir = project_dir / "src" / "main" / "resources"

    java_dir.mkdir(parents=True, exist_ok=True)
    resources_dir.mkdir(parents=True, exist_ok=True)

    (project_dir / "pom.xml").write_text(POM_XML, encoding="utf-8")
    (java_dir / "FastMinecartsPlugin.java").write_text(
        JAVA_SOURCE,
        encoding="utf-8",
    )
    (resources_dir / "plugin.yml").write_text(
        PLUGIN_YML,
        encoding="utf-8",
    )
    (resources_dir / "config.yml").write_text(
        CONFIG_YML_TEMPLATE.format(speed=initial_speed),
        encoding="utf-8",
    )


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Generate and build FastMinecarts for Paper 26.2 build 119."
    )
    parser.add_argument(
        "--speed",
        type=float,
        default=16.0,
        help="Initial maximum minecart speed in blocks/sec (default: 16).",
    )
    parser.add_argument(
        "--project-dir",
        default="FastMinecarts-project",
        help="Directory for the generated Java project.",
    )
    parser.add_argument(
        "--output",
        default="FastMinecarts.jar",
        help="Output plugin JAR path.",
    )
    parser.add_argument(
        "--no-build",
        action="store_true",
        help="Only generate the project; do not download Maven or build the JAR.",
    )
    args = parser.parse_args()

    if not (0.0 <= args.speed <= 1000.0):
        parser.error("--speed must be between 0 and 1000")

    project_dir = Path(args.project_dir).resolve()
    output_jar = Path(args.output).resolve()

    print(f"Generating {PLUGIN_NAME} {PLUGIN_VERSION}")
    print(f"Paper API: {PAPER_API_VERSION}")
    print(f"Initial speed: {args.speed:g} blocks/sec")
    print(f"Project: {project_dir}")

    write_project(project_dir, args.speed)
    print("Project files generated.")

    if args.no_build:
        print("Build skipped (--no-build).")
        return 0

    java_major = detect_java_major()
    print(f"Detected Java {java_major}")

    if java_major < 25:
        raise RuntimeError(
            f"Java {java_major} is installed, but Paper 26.2 development requires JDK 25+."
        )

    cache_dir = project_dir / ".build-tools"
    mvn = obtain_maven(cache_dir)

    print("Building plugin...")
    run(
        [str(mvn), "-q", "-DskipTests", "clean", "package"],
        cwd=project_dir,
    )

    built_jar = project_dir / "target" / "FastMinecarts.jar"
    if not built_jar.exists():
        raise RuntimeError(
            f"Build completed but JAR was not found: {built_jar}"
        )

    output_jar.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(built_jar, output_jar)

    print()
    print("SUCCESS")
    print(f"Plugin JAR: {output_jar}")
    print()
    print("Install:")
    print("  1. Stop the Paper server.")
    print(f"  2. Copy {output_jar.name} into the server's plugins/ folder.")
    print("  3. Start the server.")
    print("  4. As an OP, use: /minecartspeed 16")
    print()
    print("The plugin does NOT require the Minecart Improvements experiment.")

    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        print("\nCancelled.", file=sys.stderr)
        raise SystemExit(130)
    except Exception as exc:
        print(f"\nERROR: {exc}", file=sys.stderr)
        raise SystemExit(1)
