package io.droptracker;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
class WebhookBody
{
	private String content;
	private List<Embed> embeds = new ArrayList<>();
	private Author DropTracker = new Author("https://www.droptracker.io/",
			"DropTracker",
			"https://www.droptracker.io/img/droptracker-small.gif");

	@Data
	static class Embed
	{
		final String title = "Drop received:";
		final UrlEmbed image;
		final Author author = new Author("https://www.droptracker.io/",
				"DropTracker",
				"https://www.droptracker.io/img/droptracker-small.gif");
		final List<Field> fields = new ArrayList<>();
		public void addField(String name, String value, boolean inline) {
			this.fields.add(new Field(name, value, inline));
		}
	}

	@Data
	static class UrlEmbed
	{
		final String url;
	}
	@Data
	static class Author
	{
		final String url;
		final String name;
		final String avatar;
	}
	@Data
	static class Field
	{
		final String name;
		final String value;
		final boolean inline;
	}
}
