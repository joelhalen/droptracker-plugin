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
    private static boolean initialized = false;

    private static void setupDirectory() {
        if (initialized) {
            return; // Only setup once per application instance
        }
        
        if (!DT_DIRECTORY.exists()) {
            DT_DIRECTORY.mkdirs();
        }
        
        LOG_FILE = new File(DT_DIRECTORY, "droptracker.log");
        
        // Only rename existing log file once at startup
        if (LOG_FILE.exists()) {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss"));
            File renamedFile = new File(DT_DIRECTORY, "droptracker-" + timestamp + ".log");
            LOG_FILE.renameTo(renamedFile);
        }
        
        initialized = true;
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
