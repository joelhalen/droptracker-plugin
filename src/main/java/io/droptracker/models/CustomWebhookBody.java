package io.droptracker.models;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * Represents the JSON body sent to a Discord webhook (or the DropTracker API endpoint
 * that mirrors the Discord webhook format).
 *
 * <p>Structure mirrors Discord's webhook payload:
 * <pre>
 * {
 *   "content": "...",
 *   "embeds": [ { "title": "...", "author": {...}, "fields": [...], "image": {...} } ]
 * }
 * </pre>
 *
 * <p>Each submission produces one {@link Embed} containing structured {@link Field} entries
 * (e.g. {@code "quest_name"}, {@code "killcount"}) that the DropTracker API parses on receipt.
 * The static {@link #DropTracker} author object is attached to every embed to brand the
 * notification with the DropTracker logo in Discord.</p>
 *
 * <p>Serialized to JSON via Gson before being sent over HTTP as a multipart form part.</p>
 */
@Data
public class CustomWebhookBody
{
	/** The plain-text content of the webhook message (appears above the embeds in Discord). */
	private String content;

	/** One or more rich embeds attached to this webhook message. */
	private List<Embed> embeds = new ArrayList<>();

	/** Shared author block attached to every embed; links back to the DropTracker website. */
	private static Author DropTracker = new Author("https://www.droptracker.io/",
			"DropTracker",
			"https://www.droptracker.io/img/droptracker-small.gif");

	/**
	 * A Discord embed object. Each webhook typically contains one embed per event
	 * (drop, pet, quest, etc.) populated with structured {@link Field} data.
	 */
	@Data
	public static class Embed
	{
		/** Title text displayed at the top of the embed (e.g. {@code "Pet Drop!"}). */
		public String title = "";

		/** Optional thumbnail/image shown inside the embed (e.g. the dropped item sprite). */
		UrlEmbed image = null;

		/** Branding author block; always set to the shared {@link #DropTracker} instance. */
		final Author author = DropTracker;

		/** Ordered list of key-value fields containing the event payload data. */
		final List<Field> fields = new ArrayList<>();

		/** Convenience method to append a key-value field to this embed. */
		public void addField(String name, String value, boolean inline) {
			this.fields.add(new Field(name, value, inline));
		}

		/** Sets the embed image to the given URL (e.g. a RuneLite item image URL). */
		public void setImage(String imageUrl) {
			this.image = new UrlEmbed(imageUrl);
		}
	}

	/** Wrapper for an image or thumbnail URL within a Discord embed. */
	@Data
	public static class UrlEmbed
	{
		final String url;
	}

	/** Discord embed author block containing a display name, profile URL, and icon URL. */
	@Data
	public static class Author
	{
		final String url;
		final String name;
		final String icon_url;
	}

	/** A single key-value field within a Discord embed. */
	@Data
	public static class Field
	{
		/** The field key (e.g. {@code "quest_name"}, {@code "killcount"}). */
		final String name;
		/** The field value serialized as a string. */
		final String value;
		/** Whether Discord should render this field inline with adjacent fields. */
		final boolean inline;
	}

	/**
	 * Looks up a field value by name from the given embed.
	 *
	 * @param embed     the embed to search
	 * @param fieldName the exact field name to match
	 * @return the field value, or {@code null} if no matching field exists
	 */
	public String getField(CustomWebhookBody.Embed embed, String fieldName) {
        for (CustomWebhookBody.Field field : embed.getFields()) {
            if (field.getName().equals(fieldName)) {
                return field.getValue();
            }   
        }
        return null;
    }
}
