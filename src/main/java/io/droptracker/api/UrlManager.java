package io.droptracker.api;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

import javax.inject.Inject;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.annotations.Component;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.WidgetUtil;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.util.LinkBrowser;
import io.droptracker.util.ChatMessageUtil;
import okhttp3.HttpUrl;

import io.droptracker.DropTrackerConfig;
import io.droptracker.DropTrackerPlugin;

/* Helps determine what URL to send submissions to, populates the list on startup, etc. */
@Slf4j
public class UrlManager {

    private final DropTrackerConfig config;


    @Inject
    private DropTrackerPlugin plugin;

    @Inject
    private ClientThread clientThread;

    @Inject
    private ChatMessageUtil chatMessageUtil;

	public static final @Component int PRIVATE_CHAT_WIDGET = WidgetUtil.packComponentId(InterfaceID.PmChat.CONTAINER, 0);

    @Inject
    public UrlManager(DropTrackerConfig config, DropTrackerPlugin plugin, ClientThread clientThread, ChatMessageUtil chatMessageUtil) {
        this.config = config;
        this.plugin = plugin;
        this.clientThread = clientThread;
        this.chatMessageUtil = chatMessageUtil;
    }

    
    private static CompletableFuture<Void> endpointUrlsLoaded = new CompletableFuture<>();

    public static List<String> endpointUrls = new ArrayList<>();

    private static int webhookResetCount = 0;

	public static List<String> backupUrls = new ArrayList<>();
	
	public static Boolean usingBackups = false;


    /**
	 * Grabs a random webhook URL from a preloaded list.
	 * If not loaded yet, throws or returns null.
	 */
	public static String getRandomUrl() throws IllegalStateException {
		// Wait for URLs to be loaded, but don't block the main thread
		if (!endpointUrlsLoaded.isDone()) {
			throw new IllegalStateException("Endpoints are not yet loaded; cannot submit...");
		}
		if (endpointUrls.isEmpty()) {
			throw new IllegalStateException("No valid URLs were loaded - check logs for loading errors");
		}
		Random randomP = new Random();
		String randomUrl = endpointUrls.get(randomP.nextInt(endpointUrls.size()));
		log.debug("Selected webhook URL: {}", randomUrl.substring(0, Math.min(50, randomUrl.length())) + "...");
		return randomUrl;
	}   

    /* Determine whether the given URL is a properly-formatted Discord webhook URL or not */
    public boolean isValidDiscordWebhookUrl(HttpUrl url) {
		if (config.useApi() && url.host().equals("api.droptracker.io")) {
			return true;
		}
		// Ensure that any webhook URLs returned from the GitHub page are actual Discord webhooks
		// And not external connections of some sort
		if (!"discord.com".equals(url.host()) && !url.host().endsWith(".discord.com")) {
			if(!"discordapp.com".equals(url.host()) && !url.host().endsWith(".discordapp.com")){
				return false;
			}
		}
		List<String> segments = url.pathSegments();
		if (segments.size() >= 4 && "api".equals(segments.get(0)) && "webhooks".equals(segments.get(1))) {
			return true;
		}
		return false;
	}

    /* Fetch a new list of webhook URLs from the GitHub page */
    public void fetchNewList() throws IOException {
		if (UrlManager.webhookResetCount > 10) {
			// At this point we just stop attempting to fetch new webhooks
			// Assuming that something on the backend is broken and they're not replenishing properly
			plugin.isTracking = false;
			// isTracking prevents all event processing
			return;
		}
		// Attempt to obtain a new list
		 if (backupUrls.isEmpty()) {
			 LocalDate currentDate = LocalDate.now();

			 // Define formatter for YYYYMMDD pattern
			 DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");

			 // Format the date as YYYYMMDD string
			String dateString = currentDate.format(formatter);
			URL url = null;
			 // Print the result
			 if (usingBackups) {
				 url = new URL("https://droptracker-io.github.io/content/" + dateString + ".json");
			 } else {
				 url = new URL("https://droptracker-io.github.io/content/" + dateString + "-1.json");
			 }
			BufferedReader in = new BufferedReader(new InputStreamReader(url.openStream()));
			StringBuilder response = new StringBuilder();
			String inputLine;
			while ((inputLine = in.readLine()) != null) {
				response.append(inputLine);
			}
			in.close();

			JsonArray jsonArray = new JsonParser().parse(response.toString()).getAsJsonArray();

			for (JsonElement element : jsonArray) {
				try {
					String encrypted = element.getAsString();
					try {
						String decryptedUrl = FernetDecrypt.decryptWebhook(encrypted);
						if (decryptedUrl.contains("discord")) {
							backupUrls.add(decryptedUrl);
						} else {
							log.error("[DropTracker] Decrypted URL is not based on discord; skipping");
						}
					} catch (Exception e) {
						log.error("Decryption failed with error: " + e.getMessage());
					}
				} catch (Exception e) {
					log.error("Error processing element: " + e.getMessage());
				}
			}
			if (!backupUrls.isEmpty()) {
				// swap the sets out and clear the back-up set
				endpointUrls = backupUrls;
				backupUrls.clear();
				clientThread.invokeLater(() -> {
					chatMessageUtil.sendChatMessage("We are currently having some trouble transmitting your drops to our server...");
					chatMessageUtil.sendChatMessage("Please consider enabling our API in the plugin configuration to continue tracking seamlessly.");
				});

				UrlManager.webhookResetCount++;
				// toggle whether the current set of webhooks is from the backup endpoint or the main one
				// incase we need to grab a new set before the client restarts again.
				usingBackups = !usingBackups;
			}

		}

	}

    
    /* Open a link in the browser */
    public void openLink(String destination) {
		HttpUrl webUrl = HttpUrl.parse("https://discord.gg/dvb7yP7JJH");
		if (!destination.contains("https://")) {
			if (destination == "website" && config.useApi()) {
				webUrl = HttpUrl.parse("https://www.droptracker.io/");
			}
		} else {
			webUrl = HttpUrl.parse(destination);
		}
		if (webUrl == null) {
			return;
		}
		HttpUrl.Builder urlBuilder = webUrl.newBuilder();
		HttpUrl url = urlBuilder.build();
		LinkBrowser.browse(url.toString());
		return;
	}

    
	/* Load URLs in the background from the GitHub pages site */
	public void loadEndpoints() {
		try {
			if (endpointUrls.isEmpty()) {
				
				LocalDate currentDate = LocalDate.now();

				// Define formatter for YYYYMMDD pattern
				DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");
   
				// Format the date as YYYYMMDD string
			    String dateString = currentDate.format(formatter);
				URL url = new URL("https://droptracker-io.github.io/content/" + dateString + ".json");
				BufferedReader in = new BufferedReader(new InputStreamReader(url.openStream()));
				// Get the encryption key first from github
				URL keyUrl = new URL("https://droptracker-io.github.io/content/" + dateString + "-k.txt");
				BufferedReader keyIn = new BufferedReader(new InputStreamReader(keyUrl.openStream()));	
				String loaded_key = keyIn.readLine();
				keyIn.close();
				if (loaded_key != null) {
					FernetDecrypt.ENCRYPTION_KEY = loaded_key;
				} else {
					return;
				}
				StringBuilder response = new StringBuilder();
				String inputLine;
				while ((inputLine = in.readLine()) != null) {
					response.append(inputLine);
				}
				in.close();

				JsonArray jsonArray = new JsonParser().parse(response.toString()).getAsJsonArray();

				for (JsonElement element : jsonArray) {
					try {
						String encrypted = element.getAsString();
						try {
							String decryptedUrl = FernetDecrypt.decryptWebhook(encrypted);
							// Always load Discord webhook URLs as they're needed for both API disabled users
							// and as fallback URLs when API is enabled but fails
							if (decryptedUrl.contains("discord")) {
								endpointUrls.add(decryptedUrl);
								log.debug("Added webhook URL to endpoints list");
							} else {
								log.error("[DropTracker] Decrypted URL is not based on discord; skipping: " + decryptedUrl);
							}
						} catch (Exception e) {
							log.error("Decryption failed with error: " + e.getMessage());
						}
					} catch (Exception e) {
						log.error("Error processing element: " + e.getMessage());
					}
				}
			}
			log.info("Successfully loaded {} webhook URLs from GitHub", endpointUrls.size());
			endpointUrlsLoaded.complete(null);
		} catch (Exception e) {
			log.error("Failed to load webhook URLs from GitHub", e);
			endpointUrlsLoaded.completeExceptionally(e);
		}
	}


}
