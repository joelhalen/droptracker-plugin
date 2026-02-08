# DropTracker Bug Testing (Windows)

This repo includes a `run.bat` that can **download RuneLite source**, **copy this plugin into it**, **build RuneLite**, and **launch it** — so you can test changes without installing an entire IDE and configuring it.

## What you need (one-time setup)

- **Git for Windows** (required): used to download RuneLite automatically.
- **Java** (required): building/running RuneLite requires Java.
  - Recommended: **Java 11+** (a JDK is safest; a JRE sometimes works, but a JDK avoids issues).

## Install Git for Windows

1. Download Git for Windows from `https://git-scm.com/download/win`
2. Run the installer.
3. When asked about PATH / command line usage, choose:
   - **“Git from the command line and also from 3rd-party software”**
4. Finish installation (defaults are fine for the rest).

### Verify Git is installed

1. Open **Command Prompt** (press Windows key, type `cmd`, press Enter)
2. Run:

```bat
git --version
```

If you see a version (example: `git version 2.xx.x`), you’re good.

## Run the tester script

### Option A (recommended): double-click

1. Open the folder containing this repo (the folder with `run.bat`)
2. Double-click `run.bat`

On first run it will:
- Clone RuneLite into `.\runelite\` (inside this folder)
- Copy DropTracker into RuneLite’s plugin folder
- Build RuneLite (this can take several minutes)
- Launch RuneLite

### Option B: run from Command Prompt (best for troubleshooting)

1. Open **Command Prompt**
2. `cd` into the repo folder (the folder containing `run.bat`)
3. Run:

```bat
run.bat
```

### Option C: use an existing RuneLite checkout

If you already have RuneLite cloned somewhere else, pass that folder path:

```bat
run.bat "D:\path\to\runelite"
```

If you want to **disable** the auto-clone behavior:

```bat
run.bat --no-clone "D:\path\to\runelite"
```

## In RuneLite: enable the plugin

Once RuneLite launches:
- Click the **wrench** (Configuration)
- Search for **DropTracker**
- Enable/configure as needed for the test

## Troubleshooting

### “git is not installed or not on PATH”

- Re-run the Git installer and ensure you selected:
  - **“Git from the command line and also from 3rd-party software”**
- Close and reopen Command Prompt and retry `git --version`.

### “java is not recognized…” / build fails immediately

- Install a JDK (recommended) such as:
  - Adoptium Temurin JDK (Java 11 or newer)
- After installing, open a new Command Prompt and run:

```bat
java -version
```

If that fails, Java isn’t on your PATH yet.

### “...exists but is not a RuneLite checkout”

This happens when the script expects `.\runelite\` to be RuneLite, but that folder already exists and isn’t correct.

Fix:
- Delete or rename the `runelite` folder inside this repo, then run `run.bat` again
- Or run `run.bat "D:\path\to\runelite"` to point to the correct folder

### Gradle/RuneLite build errors

Common fixes:
- Run again (first build downloads a lot; transient network issues happen)
- Ensure you have enough disk space (RuneLite + Gradle caches can be a few GB)
- Temporarily disable aggressive antivirus “Controlled folder access” if it blocks Gradle writing to disk
- If you’re behind a proxy/firewall, GitHub/Gradle downloads may be blocked

### It “hangs” on the first run

First run can take a while because it may:
- Download RuneLite source (Git clone)
- Download Gradle dependencies
- Compile RuneLite

If it’s still stuck after ~10–15 minutes, run it from Command Prompt (Option B) and send the full output to whoever is collecting bug reports.

## What to include when reporting a bug

- What you did (steps)
- What you expected vs what happened
- Any screenshots/videos
- The **full `run.bat` output** (if it failed)
