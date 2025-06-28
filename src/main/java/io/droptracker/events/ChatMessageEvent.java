package io.droptracker.events;

import com.google.common.collect.ImmutableMap;
import com.google.inject.Inject;
import io.droptracker.DropTrackerConfig;
import io.droptracker.models.BossNotification;
import io.droptracker.DropTrackerPlugin;
import io.droptracker.models.CombatAchievement;
import io.droptracker.models.CustomWebhookBody;
import io.droptracker.models.Drop;
import io.droptracker.util.ItemIDSearch;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.annotations.Varbit;
import net.runelite.api.annotations.Varp;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.chatcommands.ChatCommandsPlugin;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.plugins.loottracker.LootTrackerPlugin;
import net.runelite.http.api.loottracker.LootRecordType;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.temporal.Temporal;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static io.droptracker.util.KCService.lastDrop;
import static java.time.temporal.ChronoField.*;
import static org.apache.commons.lang3.ObjectUtils.defaultIfNull;

@Slf4j
public class ChatMessageEvent {
    @Inject
    private final DropTrackerPlugin plugin;
    private final DropTrackerConfig config;

    @Inject
    protected Client client;



    @Inject
    private ConfigManager configManager;

    @Inject
    private ClientThread clientThread;

    private ItemIDSearch itemIDFinder;

    @Inject
    public ChatMessageEvent(DropTrackerPlugin plugin, DropTrackerConfig config, ItemIDSearch itemIDFinder,
                            ScheduledExecutorService executor, ConfigManager configManager) {
        this.plugin = plugin;
        this.config = config;
        this.itemIDFinder = itemIDFinder;
        this.executor = executor;
        this.configManager = configManager;
    }



   


    private static final String BA_BOSS_NAME = "Penance Queen";
    public static final String GAUNTLET_NAME = "Gauntlet", GAUNTLET_BOSS = "Crystalline Hunllef";

    public static final String CG_NAME = "Corrupted Gauntlet", CG_BOSS = "Corrupted Hunllef";
    private static final String TOA = "Tombs of Amascut";
    private static final String TOB = "Theatre of Blood";
    private static final String COX = "Chambers of Xeric";
    @VisibleForTesting
    static final int MAX_BAD_TICKS = 10;

    private final AtomicInteger badTicks = new AtomicInteger();
    private final AtomicReference<BossNotification> bossData = new AtomicReference<>();
    

    private final ScheduledExecutorService executor;


    public boolean isEnabled() {
        return true;
    }

    public void onGameMessage(String message) {
        if (!isEnabled()) return;
    }


    @SuppressWarnings("null")
    private boolean isCorruptedGauntlet(LootReceived event) {
        return event.getType() == LootRecordType.EVENT && plugin.lastDrop != null && "The Gauntlet".equals(event.getName())
                && (CG_NAME.equals(plugin.lastDrop.getSource()) || CG_BOSS.equals(plugin.lastDrop.getSource()));
    }



    @SuppressWarnings("null")
    public String getStandardizedSource(LootReceived event) {
        if (isCorruptedGauntlet(event)) {
            return CG_NAME;
        } else if (plugin.lastDrop != null && shouldUseChatName(event)) {
            return plugin.lastDrop.getSource(); // distinguish entry/expert/challenge modes
        }
        return event.getName();
    }

    private boolean shouldUseChatName(LootReceived event) {
        assert plugin.lastDrop != null;
        String lastSource = plugin.lastDrop.getSource();
        Predicate<String> coincides = source -> source.equals(event.getName()) && lastSource.startsWith(source);
        return coincides.test(TOA) || coincides.test(TOB) || coincides.test(COX);
    }

    public void onWidget(WidgetLoaded event) {
        if (!isEnabled())
            return;
        /* Handle BA events */
        if (event.getGroupId() == InterfaceID.BA_REWARD) {
            Widget widget = client.getWidget(ComponentID.BA_REWARD_REWARD_TEXT);
            if (widget != null && widget.getText().contains("80 ") && widget.getText().contains("5 ")) {
                int gambleCount = client.getVarbitValue(Varbits.BA_GC);
                BossNotification notification = new BossNotification(BA_BOSS_NAME, gambleCount, "The Queen is dead!", null, null,null);
                bossData.set(notification);
            }
        }
    }


    

}

