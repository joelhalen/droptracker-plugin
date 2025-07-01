package io.droptracker.util;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import net.runelite.client.RuneLite;

public class DebugLogger {
    private static final File DT_DIRECTORY = new File(RuneLite.RUNELITE_DIR, "dt-logs");
    private static File LOG_FILE;
    private static boolean initialized = false;
    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();

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
        try (FileWriter writer = new FileWriter(LOG_FILE, true)) {
            String currentTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            writer.write("[" + currentTime + "] " + message + "\n");
            
            // Check if the message contains JSON data
            if (message.contains("raw json:")) {
                String jsonPart = extractJsonFromMessage(message);
                if (jsonPart != null && !jsonPart.isEmpty()) {
                    try {
                        // Parse the JSON
                        JsonParser parser = new JsonParser();
                        JsonElement jsonElement = parser.parse(jsonPart);
                        
                        // Simplify and pretty-print the JSON
                        JsonElement simplified = simplifyWebhookJson(jsonElement);
                        String prettyJson = PRETTY_GSON.toJson(simplified);
                        
                        // Write the pretty-printed JSON with proper indentation
                        writer.write("    JSON Data:\n");
                        String[] lines = prettyJson.split("\n");
                        for (String line : lines) {
                            writer.write("    " + line + "\n");
                        }
                        writer.write("\n"); // Extra newline for readability between entries
                    } catch (JsonSyntaxException e) {
                        // If JSON parsing fails, just log the error
                        writer.write("    [Error parsing JSON: " + e.getMessage() + "]\n");
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    /**
     * Simplifies webhook JSON by converting field arrays to key-value objects
     * @param element The JSON element to simplify
     * @return The simplified JSON element
     */
    private static JsonElement simplifyWebhookJson(JsonElement element) {
        if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            JsonObject simplified = new JsonObject();
            
            // Copy all properties
            for (String key : obj.keySet()) {
                if (key.equals("embeds") && obj.get(key).isJsonArray()) {
                    // Special handling for embeds array
                    JsonArray embeds = obj.getAsJsonArray("embeds");
                    JsonArray simplifiedEmbeds = new JsonArray();
                    
                    for (JsonElement embed : embeds) {
                        simplifiedEmbeds.add(simplifyEmbed(embed));
                    }
                    
                    simplified.add("embeds", simplifiedEmbeds);
                } else {
                    simplified.add(key, obj.get(key));
                }
            }
            
            return simplified;
        }
        
        return element;
    }
    
    /**
     * Simplifies an embed object by converting fields array to key-value pairs
     * @param embedElement The embed JSON element
     * @return The simplified embed
     */
    private static JsonElement simplifyEmbed(JsonElement embedElement) {
        if (!embedElement.isJsonObject()) {
            return embedElement;
        }
        
        JsonObject embed = embedElement.getAsJsonObject();
        JsonObject simplifiedEmbed = new JsonObject();
        
        // Copy all properties except fields
        for (String key : embed.keySet()) {
            if (key.equals("fields") && embed.get(key).isJsonArray()) {
                // Convert fields array to key-value object
                JsonArray fields = embed.getAsJsonArray("fields");
                JsonObject fieldData = new JsonObject();
                
                for (JsonElement field : fields) {
                    if (field.isJsonObject()) {
                        JsonObject fieldObj = field.getAsJsonObject();
                        if (fieldObj.has("name") && fieldObj.has("value")) {
                            String fieldName = fieldObj.get("name").getAsString();
                            String fieldValue = fieldObj.get("value").getAsString();
                            fieldData.addProperty(fieldName, fieldValue);
                        }
                    }
                }
                
                simplifiedEmbed.add("fields", fieldData);
            } else {
                simplifiedEmbed.add(key, embed.get(key));
            }
        }
        
        return simplifiedEmbed;
    }
    
    /**
     * Extracts JSON content from a log message that contains "raw json:" prefix
     * @param message The log message
     * @return The JSON string, or null if not found
     */
    private static String extractJsonFromMessage(String message) {
        String prefix = "raw json: ";
        int startIndex = message.indexOf(prefix);
        if (startIndex != -1) {
            return message.substring(startIndex + prefix.length()).trim();
        }
        return null;
    }
}
