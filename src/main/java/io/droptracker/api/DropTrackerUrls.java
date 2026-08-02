package io.droptracker.api;

import java.util.regex.Pattern;

import javax.annotation.Nullable;

import okhttp3.HttpUrl;

/**
 * Every host this plugin is capable of connecting to, as compile-time constants.
 *
 * <p>The Plugin Hub requires that all URLs a plugin talks to are either hardcoded
 * in the plugin or typed by the user, so that the set of domains can be reviewed
 * exhaustively. The API therefore hands us <em>paths</em> (for example
 * {@code "itemdb/4151.png"}), never URLs, and this class anchors them onto one of
 * the bases below. A server response can influence which file we ask for; it can
 * never influence which host we ask.
 *
 * <p>The only address not listed here is the API base itself, which is resolved by
 * {@link DropTrackerApi#getApiUrl()} — either the {@link #DEFAULT_API} constant or
 * the endpoint the user typed into the hidden {@code customApiEndpoint} setting.
 */
public final class DropTrackerUrls {

	/** Website root: panel links, and the parent of {@link #IMAGES}. */
	public static final HttpUrl WEB = HttpUrl.get("https://www.droptracker.io/");

	/** Everything the plugin renders: item/npc icons, lootboards, submission screenshots. */
	public static final HttpUrl IMAGES = HttpUrl.get("https://www.droptracker.io/img/");

	/** Static published content: the webhook list, its key, and the item-id lists. */
	public static final HttpUrl CONTENT = HttpUrl.get("https://droptracker-io.github.io/content/");

	/** RuneLite's item cache, used to resolve item names to ids. */
	public static final HttpUrl ITEM_CACHE = HttpUrl.get("https://static.runelite.net/cache/item/");

	/** Discord webhook delivery. Submissions are POSTed to {@code base + id + token}. */
	public static final HttpUrl DISCORD_WEBHOOK = HttpUrl.get("https://discord.com/api/webhooks/");

	/** Discord invite links opened in the user's browser from the group panel. */
	public static final HttpUrl DISCORD_INVITE = HttpUrl.get("https://discord.gg/");

	/** API base used unless the user has typed their own endpoint. */
	public static final String DEFAULT_API = "https://api.droptracker.io";

	/**
	 * Characters no server-supplied path may contain: {@code :} (so nothing
	 * resembling a scheme survives), {@code %} (so nothing arrives pre-encoded and
	 * gets decoded twice), {@code ?} and {@code #} (so nothing can append a query or
	 * fragment), {@code \} and control characters.
	 *
	 * <p>Everything else is allowed and percent-encoded on the way out. Real
	 * screenshot paths contain apostrophes and parentheses ({@code Kree'arra},
	 * {@code Dagannoth_Rex_(hard)}), and it is {@code addPathSegment} — which
	 * encodes {@code /} too — that makes the host unreachable from a path, not the
	 * character set.
	 */
	private static final Pattern FORBIDDEN = Pattern.compile("[\\\\%?#:\\p{Cntrl}]");

	private static final int MAX_PATH_LENGTH = 512;
	private static final int MAX_SEGMENT_LENGTH = 128;

	private static final Pattern WEBHOOK_ID = Pattern.compile("[0-9]{5,32}");
	private static final Pattern WEBHOOK_TOKEN = Pattern.compile("[A-Za-z0-9._-]{16,200}");
	private static final Pattern INVITE_CODE = Pattern.compile("[A-Za-z0-9-]{1,64}");

	private DropTrackerUrls() {
	}

	/**
	 * Anchors a server-supplied relative path onto a compile-time base, or returns
	 * null if the path is anything other than a plain relative path.
	 *
	 * <p>Rejects absolute URLs, absolute paths, {@code .} / {@code ..} traversal,
	 * empty segments, and any {@link #FORBIDDEN} character. Callers treat null the
	 * same as "no image", so a rejected path simply renders nothing.
	 */
	@Nullable
	public static HttpUrl underBase(HttpUrl base, @Nullable String relativePath) {
		if (relativePath == null) {
			return null;
		}
		String path = relativePath.trim();
		if (path.isEmpty() || path.length() > MAX_PATH_LENGTH
				|| path.startsWith("/")
				|| FORBIDDEN.matcher(path).find()) {
			return null;
		}
		HttpUrl.Builder builder = base.newBuilder();
		for (String segment : path.split("/")) {
			if (segment.isEmpty() || segment.length() > MAX_SEGMENT_LENGTH
					|| ".".equals(segment) || "..".equals(segment)) {
				return null;
			}
			builder.addPathSegment(segment);
		}
		return builder.build();
	}

	/** {@code https://www.droptracker.io/img/<relativePath>} — item icons, lootboards, screenshots. */
	@Nullable
	public static HttpUrl image(@Nullable String relativePath) {
		return underBase(IMAGES, relativePath);
	}

	/**
	 * A page on the website, for opening in the user's browser.
	 *
	 * <p>Unlike {@link #underBase} this encodes rather than rejects, because some
	 * segments are legitimately free-form (a player name may contain spaces).
	 * {@code addPathSegment} percent-encodes {@code /} as well, so no segment can
	 * add depth or escape the host.
	 */
	public static HttpUrl web(String... segments) {
		HttpUrl.Builder builder = WEB.newBuilder();
		for (String segment : segments) {
			builder.addPathSegment(segment);
		}
		return builder.build();
	}

	/** {@code https://droptracker-io.github.io/content/<fileName>}. */
	@Nullable
	public static HttpUrl content(@Nullable String fileName) {
		return underBase(CONTENT, fileName);
	}

	/** {@code https://static.runelite.net/cache/item/<fileName>}. */
	@Nullable
	public static HttpUrl itemCache(@Nullable String fileName) {
		return underBase(ITEM_CACHE, fileName);
	}

	/**
	 * {@code https://discord.com/api/webhooks/<id>/<token>}. The id and token are
	 * opaque credentials published by the backend; the host is ours to decide.
	 */
	@Nullable
	public static HttpUrl discordWebhook(@Nullable String id, @Nullable String token) {
		if (id == null || token == null
				|| !WEBHOOK_ID.matcher(id).matches()
				|| !WEBHOOK_TOKEN.matcher(token).matches()) {
			return null;
		}
		return DISCORD_WEBHOOK.newBuilder()
			.addPathSegment(id)
			.addPathSegment(token)
			.build();
	}

	/** {@code https://discord.gg/<code>} for a group's public invite. */
	@Nullable
	public static HttpUrl discordInvite(@Nullable String code) {
		if (code == null || !INVITE_CODE.matcher(code.trim()).matches()) {
			return null;
		}
		return DISCORD_INVITE.newBuilder().addPathSegment(code.trim()).build();
	}
}
