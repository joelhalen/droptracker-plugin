package io.droptracker;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import net.runelite.client.RuneLite;

public class DebugLogger {
    private static final File LOG_FILE = new File(RuneLite.RUNELITE_DIR, "dt-logs.txt");

    public static void logSubmission(String message) {
        try {
            FileWriter writer = new FileWriter(LOG_FILE, true);
            writer.write(message + "\n");
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
