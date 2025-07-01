package io.droptracker.models.submissions;
/*
*  Author: https://github.com/pajlads/DinkPlugin
* */
import lombok.Value;
import net.runelite.client.game.ItemStack;
import net.runelite.http.api.loottracker.LootRecordType;

import java.time.Instant;
import java.util.Collection;

@Value
public class Drop {
    String source;
    LootRecordType category;
    Collection<ItemStack> items;
    Instant time = Instant.now();

    public static String getAction(LootRecordType type) {
        switch (type) {
            case NPC:
                return "Kill";
            case PLAYER:
                return "Player Kill";
            case PICKPOCKET:
                return "Pickpocket";
            default:
                return "Completion";
        }
    }
}
