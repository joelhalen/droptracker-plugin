package io.droptracker.models;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class CustomWebhookBody
{
	private String content;
	private List<Embed> embeds = new ArrayList<>();
	private static Author DropTracker = new Author("https://www.droptracker.io/",
			"DropTracker",
			"https://www.droptracker.io/img/droptracker-small.gif");

	@Data
	public static class Embed
	{
		public String title = "";
		UrlEmbed image = null;
		final Author author = DropTracker;
		final List<Field> fields = new ArrayList<>();
		public void addField(String name, String value, boolean inline) {
			this.fields.add(new Field(name, value, inline));
		}
		public void setImage(String imageUrl) {
			this.image = new UrlEmbed(imageUrl);
		}
	}

	@Data
	public static class UrlEmbed
	{
		final String url;
	}
	@Data
	public static class Author
	{
		final String url;
		final String name;
		final String icon_url;
	}
	@Data
	public static class Field
	{
		final String name;
		final String value;
		final boolean inline;
	}
}
