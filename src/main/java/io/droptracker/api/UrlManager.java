package io.droptracker.api;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import javax.annotation.Nullable;
import javax.inject.Inject;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;
import io.droptracker.util.ChatMessageUtil;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

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

    @Inject
    private ScheduledExecutorService executor;

    @Inject
    private OkHttpClient httpClient;

    @Inject
    private Gson gson;

    private static final Random RANDOM = new Random();

    /** How many times the initial endpoint load has failed; retried with backoff. */
    private final AtomicInteger loadAttempts = new AtomicInteger(0);

    private static final int MAX_LOAD_ATTEMPTS = 5;

    @Inject
    public UrlManager(DropTrackerConfig config, DropTrackerPlugin plugin, ClientThread clientThread, ChatMessageUtil chatMessageUtil) {
        this.config = config;
        this.plugin = plugin;
        this.clientThread = clientThread;
        this.chatMessageUtil = chatMessageUtil;
    }

    
    private static CompletableFuture<Void> endpointUrlsLoaded = new CompletableFuture<>();

    public static List<WebhookEndpoint> endpoints = new ArrayList<>();

    private static int webhookResetCount = 0;

	public static List<WebhookEndpoint> backupEndpoints = new ArrayList<>();

	public static Boolean usingBackups = false;

	/** Everything after {@code /api/webhooks/} in a published entry, if it is written as a URL. */
	private static final Pattern WEBHOOK_PATH = Pattern.compile(".*?/api/webhooks/", Pattern.DOTALL);

	/**
	 * A Discord webhook as the two opaque credentials it actually is. The host is
	 * never taken from the published list — see {@link #url()}.
	 */
	public static final class WebhookEndpoint {
		private final String id;
		private final String token;

		WebhookEndpoint(String id, String token) {
			this.id = id;
			this.token = token;
		}

		/** Always under the hardcoded {@link DropTrackerUrls#DISCORD_WEBHOOK} base. */
		public HttpUrl url() {
			return DropTrackerUrls.discordWebhook(id, token);
		}
	}

	/**
	 * Turns one decrypted list entry into a webhook credential pair, or null if it
	 * is not one.
	 *
	 * <p>Accepts both the legacy format (a full {@code https://discord.com/api/webhooks/<id>/<token>}
	 * URL) and the current one (a bare {@code <id>/<token>} pair), so the plugin works
	 * against a list published either way. In both cases only the last two path
	 * segments are read — whatever host the entry may claim is discarded, never
	 * parsed, and never connected to.
	 */
	@Nullable
	static WebhookEndpoint parseEndpoint(@Nullable String plaintext) {
		if (plaintext == null) {
			return null;
		}
		String candidate = plaintext.trim();
		// Drop any host/prefix a legacy entry carries, plus any query or fragment.
		candidate = WEBHOOK_PATH.matcher(candidate).replaceFirst("");
		int cut = candidate.indexOf('?');
		if (cut < 0) {
			cut = candidate.indexOf('#');
		}
		if (cut >= 0) {
			candidate = candidate.substring(0, cut);
		}
		String[] parts = candidate.split("/");
		if (parts.length != 2) {
			return null;
		}
		// discordWebhook() is the validator: it returns null unless both parts are
		// well-formed credentials, which is exactly the condition for accepting one.
		if (DropTrackerUrls.discordWebhook(parts[0], parts[1]) == null) {
			return null;
		}
		return new WebhookEndpoint(parts[0], parts[1]);
	}

    /**
	 * Grabs a random webhook endpoint from the preloaded list.
	 * If not loaded yet, throws.
	 */
	public static HttpUrl getRandomEndpoint() throws IllegalStateException {
		// Wait for endpoints to be loaded, but don't block the main thread
		if (!endpointUrlsLoaded.isDone()) {
			throw new IllegalStateException("Endpoints are not yet loaded; cannot submit...");
		}
		if (endpoints.isEmpty()) {
			throw new IllegalStateException("No valid endpoints were loaded - check logs for loading errors");
		}
		return endpoints.get(RANDOM.nextInt(endpoints.size())).url();
	}

    /* Determine whether the given URL is a properly-formatted Discord webhook URL or not */
    public boolean isValidDiscordWebhookUrl(HttpUrl url) {
		if (config.useApi() && (url.host().equals("api.droptracker.io") || !config.customApiEndpoint().equals(""))) {
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
		 if (backupEndpoints.isEmpty()) {
			 LocalDate currentDate = LocalDate.now();

			 // Define formatter for YYYYMMDD pattern
			 DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");

			 // Format the date as YYYYMMDD string
			String dateString = currentDate.format(formatter);
			HttpUrl url = usingBackups
					? DropTrackerUrls.content(dateString + ".json")
					: DropTrackerUrls.content(dateString + "-1.json");

			JsonArray jsonArray = gson.fromJson(httpGetString(url), JsonArray.class);

			for (JsonElement element : jsonArray) {
				try {
					String encrypted = element.getAsString();
					try {
						WebhookEndpoint endpoint = parseEndpoint(FernetDecrypt.decryptWebhook(encrypted));
						if (endpoint != null) {
							backupEndpoints.add(endpoint);
						} else {
							log.error("[DropTracker] Decrypted entry is not a webhook credential; skipping");
						}
					} catch (Exception e) {
						log.error("Decryption failed: {}", e.getMessage());
					}
				} catch (Exception e) {
					log.error("Error processing element: {}", e.getMessage());
				}
			}
			if (!backupEndpoints.isEmpty()) {
				// COPY the freshly fetched list, then clear the backing one.
				// Assigning the reference made both fields the same ArrayList,
				// so the clear() below emptied the list we had just installed:
				// every webhook submission after the first refresh threw
				// IllegalStateException ("no urls") and was silently dropped,
				// and the recovery path could never repopulate because
				// backupEndpoints was permanently the same (empty) object. Ten
				// rounds of that flipped isTracking off entirely.
				endpoints = new ArrayList<>(backupEndpoints);
				backupEndpoints.clear();
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

    
	/* Load webhook credentials in the background from the GitHub pages site */
	public void loadEndpoints() {
		try {
			if (endpoints.isEmpty()) {

				LocalDate currentDate = LocalDate.now();

				// Define formatter for YYYYMMDD pattern
				DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");

				// Format the date as YYYYMMDD string
			    String dateString = currentDate.format(formatter);
				// Get the encryption key first from github (first line only, matching the prior readLine())
				String keyBody = httpGetString(DropTrackerUrls.content(dateString + "-k.txt"));
				String loadedKey = keyBody.split("\\R", 2)[0].trim();
				if (!loadedKey.isEmpty()) {
					FernetDecrypt.ENCRYPTION_KEY = loadedKey;
				} else {
					// Treat a missing key like any other load failure so the retry
					// logic below runs instead of leaving the future forever pending.
					throw new IOException("Encryption key endpoint returned no content");
				}

				String responseBody = httpGetString(DropTrackerUrls.content(dateString + ".json"));
				JsonArray jsonArray = gson.fromJson(responseBody, JsonArray.class);

				for (JsonElement element : jsonArray) {
					try {
						String encrypted = element.getAsString();
						try {
							// Always load webhook credentials as they're needed for both API disabled
							// users and as a fallback when API is enabled but fails
							WebhookEndpoint endpoint = parseEndpoint(FernetDecrypt.decryptWebhook(encrypted));
							if (endpoint != null) {
								endpoints.add(endpoint);
							} else {
								log.error("Decrypted entry is not a Discord webhook credential; skipping");
							}
						} catch (Exception e) {
							log.error("Decryption failed: {}", e.getMessage());
						}
					} catch (Exception e) {
						log.error("Error processing element: {}", e.getMessage());
					}
				}
			}
			log.debug("Successfully loaded {} webhook endpoints from GitHub", endpoints.size());
			endpointUrlsLoaded.complete(null);
		} catch (Exception e) {
			// A transient network failure at client startup used to permanently
			// disable webhook-mode submissions (the future completed exceptionally
			// and nothing ever retried). Retry with linear backoff instead.
			int attempt = loadAttempts.incrementAndGet();
			if (attempt < MAX_LOAD_ATTEMPTS) {
				long delaySeconds = 30L * attempt;
				log.warn("Failed to load webhook endpoints from GitHub (attempt {}/{}); retrying in {}s",
					attempt, MAX_LOAD_ATTEMPTS, delaySeconds, e);
				executor.schedule(this::loadEndpoints, delaySeconds, TimeUnit.SECONDS);
			} else {
				log.error("Failed to load webhook endpoints from GitHub after {} attempts; giving up", attempt, e);
				endpointUrlsLoaded.completeExceptionally(e);
			}
		}
	}

	/** Blocking GET returning the response body as a String, via the injected shared OkHttpClient. */
	private String httpGetString(@Nullable HttpUrl url) throws IOException {
		if (url == null) {
			throw new IOException("Refusing to fetch a rejected content URL");
		}
		Request request = new Request.Builder().url(url).build();
		try (Response response = httpClient.newCall(request).execute()) {
			if (!response.isSuccessful()) {
				throw new IOException("Request to " + url + " failed: HTTP " + response.code());
			}
			ResponseBody body = response.body();
			if (body == null) {
				throw new IOException("Empty response body from " + url);
			}
			return body.string();
		}
	}
}
