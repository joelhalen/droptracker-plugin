package io.droptracker.models.api;

import com.google.gson.annotations.SerializedName;
import lombok.Getter;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Payloads of the event window ({@code /plugin/events...}). The server composes
 * every page as a list of blocks from a closed set (heading, text, stats,
 * table, tasks, board) holding plain text; the window draws the types it
 * knows and skips the rest. The only action is opening another page.
 */
@Getter
public class EventView {
    private Event event;
    private List<Page> pages = new ArrayList<>();
    private String page;
    private List<Block> blocks = new ArrayList<>();
    /** The viewer's own team, when rostered. */
    @Nullable
    private Team team;

    /** {@code GET /plugin/events}: what this account may open. */
    @Getter
    public static class Listing {
        private List<Event> events = new ArrayList<>();
    }

    @Getter
    public static class Event {
        private int id;
        private String name;
        private String subtitle;
        /** "active" | "draft" | "past". */
        private String status;
        @Nullable
        private String group;
        @Nullable
        private Team team;
    }

    @Getter
    public static class Team {
        private int id;
        private String name;
        @Nullable
        private String color;
    }

    @Getter
    public static class Page {
        private String key;
        private String label;
    }

    @Getter
    public static class Block {
        private String type;
        @Nullable
        private String text;
        /** "muted", or null. */
        @Nullable
        private String tone;
        private List<Item> items = new ArrayList<>();
        private List<Column> columns = new ArrayList<>();
        private List<Row> rows = new ArrayList<>();
        @Nullable
        private String empty;
        /** board: whose view to show; null is every team's. */
        @SerializedName("team_id")
        @Nullable
        private Integer teamId;
    }

    /** A stat tile (label, value) or a task (label, bars...). */
    @Getter
    public static class Item {
        @Nullable
        private String label;
        @Nullable
        private String value;
        @Nullable
        private String detail;
        @Nullable
        private String tip;
        @Nullable
        private String points;
        @SerializedName("icon_item_id")
        @Nullable
        private Integer iconItemId;
        private List<Bar> bars = new ArrayList<>();
    }

    @Getter
    public static class Bar {
        private String label;
        @Nullable
        private String color;
        private long have;
        private long need;
        private boolean done;
        private String text;
        private boolean highlight;
    }

    @Getter
    public static class Column {
        private String label;
        private int width = 1;
        private boolean right;
    }

    @Getter
    public static class Row {
        private List<String> cells = new ArrayList<>();
        @Nullable
        private String color;
        @Nullable
        private String page;
        @Nullable
        private String tip;
        private boolean highlight;
    }
}
