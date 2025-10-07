package io.droptracker.util;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;

import io.droptracker.DropTrackerConfig;
import javax.inject.Inject;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
@Slf4j
public class DebugLogger {

    private static final String PLUGIN_DIR_NAME = "droptracker";
    private static final String LOGS_DIR_NAME = "logs";
    private static final String BASE_LOG_FILENAME = "droptracker.log";

    private static volatile DebugLogger activeInstance;

    private final DropTrackerConfig config;
    private final File logsDir;
    private final File currentLogFile;
    private final Object writeLock = new Object();
    private FileWriter writer;

    @Inject
    public DebugLogger(DropTrackerConfig config) {
        this.config = config;

        File pluginDir = new File(RuneLite.RUNELITE_DIR, PLUGIN_DIR_NAME);
        this.logsDir = new File(pluginDir, LOGS_DIR_NAME);
        if (!logsDir.exists()) {
            logsDir.mkdirs();
        }

        this.currentLogFile = new File(logsDir, BASE_LOG_FILENAME);
        rotateIfExists(currentLogFile);

        try {
            this.writer = new FileWriter(currentLogFile, true);
        } catch (IOException e) {
            // If we cannot open the writer, logging will become a no-op
            this.writer = null;
        }

        activeInstance = this;
    }

    public boolean isEnabled() {
        return config.debugLogging();
    }

    public static void log(String message) {
        DebugLogger instance = activeInstance;
        if (instance == null || !instance.isEnabled()) {
            return;
        }
        log.debug("DebugLogger message: " + message);
        instance.writeLine(message);
    }

    private void writeLine(String message) {
        synchronized (writeLock) {
            if (writer == null) {
                return;
            }
            try {
                writer.write(message + System.lineSeparator());
                writer.flush();
            } catch (IOException e) {
                // Swallow to avoid impacting game thread; debugging only
            }
        }
    }

    private void rotateIfExists(File file) {
        if (!file.exists()) {
            return;
        }

        long lastModified = file.lastModified();
        String ts = new SimpleDateFormat("yyyy-MM-dd_HHmmss").format(new Date(lastModified));
        File target = new File(logsDir, "droptracker-" + ts + ".log");

        if (target.exists()) {
            int idx = 1;
            File candidate;
            do {
                candidate = new File(logsDir, "droptracker-" + ts + "-" + idx + ".log");
                idx++;
            } while (candidate.exists());
            target = candidate;
        }

        try {
            Files.move(file.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException moveEx) {
            // Fallback to simple numeric roll if atomic move fails
            int idx = 1;
            File candidate;
            do {
                candidate = new File(logsDir, "droptracker-" + idx + ".log");
                idx++;
            } while (candidate.exists());
            // Best-effort rename; ignore result
            //noinspection ResultOfMethodCallIgnored
            file.renameTo(candidate);
        }
    }

    public void close() {
        synchronized (writeLock) {
            if (writer != null) {
                try {
                    writer.flush();
                    writer.close();
                } catch (IOException e) {
                    // Ignore on close
                } finally {
                    writer = null;
                }
            }
            if (activeInstance == this) {
                activeInstance = null;
            }
        }
    }
}