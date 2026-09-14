package io.droptracker.models.api;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.annotations.SerializedName;

import lombok.Getter;

/**
 * The logged-in account's own death messages, from {@code GET/POST
 * /player/death_messages}.
 *
 * <p>A member writes up to five lines; the server picks one for each death in
 * every group that lets members write their own. The response carries the
 * limits, the placeholders and, per group, whether the message is posted there,
 * so the editor never has to guess any of them. Defaults stand in for a field
 * an older server leaves out.
 */
@Getter
public class DeathMessages {

    @SerializedName("player_name")
    private String playerName;

    @SerializedName("max_messages")
    private int maxMessages = 5;

    @SerializedName("max_length")
    private int maxLength = 150;

    private List<String> messages = new ArrayList<>();

    private List<Token> tokens = new ArrayList<>();

    private List<Group> groups = new ArrayList<>();

    /** One placeholder a message may use, with a sample value for previews. */
    @Getter
    public static class Token {
        private String token;
        private String help;
        private String sample;
    }

    /** A group the account belongs to, and whether its message is posted there. */
    @Getter
    public static class Group {
        private int id;
        private String name;
        /** The group turned on members' own death messages. */
        private boolean allowed;
        /** The group's leaders blocked this member's own messages. */
        private boolean blocked;
    }
}
