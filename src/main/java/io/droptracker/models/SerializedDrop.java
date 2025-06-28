package io.droptracker.models;

/* Author: Dink Plugin */

import lombok.AccessLevel;
import lombok.Data;
import lombok.Setter;

/**
 * Contains kill count observed by base runelite loot tracker plugin, stored in profile configuration.
 *
 * @see <a href="https://github.com/runelite/runelite/blob/master/runelite-client/src/main/java/net/runelite/client/plugins/loottracker/ConfigLoot.java#L41">RuneLite class</a>
 */
@Data
@Setter(AccessLevel.PRIVATE)
public class SerializedDrop {
    private int kills;
}
