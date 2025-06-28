package io.droptracker;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import net.runelite.client.RuneLite;

public class DebugLogger {
    private static final File DT_DIRECTORY = new File(RuneLite.RUNELITE_DIR, "dt-logs");

    private static File LOG_FILE;

    private static void setupDirectory() {
        if (!DT_DIRECTORY.exists()) {
            DT_DIRECTORY.mkdirs();
        }
        LOG_FILE = new File(DT_DIRECTORY, "droptracker.log");
        if (LOG_FILE.exists()) {
            LOG_FILE.renameTo(new File(DT_DIRECTORY, "droptracker-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss")) + ".log"));
        }
    }
    public static void logSubmission(String message) {
        setupDirectory();
        try {
            FileWriter writer = new FileWriter(LOG_FILE, true);
            String currentTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            writer.write("[" + currentTime + "] " + message + "\n");
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
