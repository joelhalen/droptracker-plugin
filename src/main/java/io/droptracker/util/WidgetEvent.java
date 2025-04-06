/*
 * Copyright (c) 2017. l2-
 * Copyright (c) 2017, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Borrowed from ChatCommandsPlugin and modified for use within the
 * DropTracker by @joelhalen <andy@joelhalen.net>
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package io.droptracker.util;

import com.google.common.annotations.VisibleForTesting;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Inject;
import io.droptracker.DropTrackerConfig;
import io.droptracker.DropTrackerPlugin;
import io.droptracker.models.CustomWebhookBody;
import io.droptracker.models.Pet;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.IndexedSprite;
import net.runelite.api.events.*;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.Text;
import org.apache.commons.text.WordUtils;

import java.awt.image.BufferedImage;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class WidgetEvent {

    @Inject
    private final DropTrackerPlugin plugin;
    private final DropTrackerConfig config;

    @Inject
    protected Client client;

    @Inject
    private ConfigManager configManager;

    @Inject
    private ClientThread clientThread;

    @Inject
    private ItemManager itemManager;

    @Inject
    private Gson gson;

    private int petsIconIdx = -1;
    private int[] pets;

    @Inject
    private ScheduledExecutorService executor;

    private boolean advLogLoaded = false;
    private boolean bossLogLoaded = false;
    private boolean scrollInterfaceLoaded = false;
    private String pohOwner;

    private static final String TEAM_SIZES = "(?<teamsize>\\d+(?:\\+|-\\d+)? players?|Solo)";

    private static final Pattern ADVENTURE_LOG_TITLE_PATTERN = Pattern.compile("The Exploits of (.+)");
    private static final Pattern ADVENTURE_LOG_PB_PATTERN = Pattern.compile("Fastest (?:kill|run|Room time)(?: - \\(Team size: \\(?" + TEAM_SIZES + "\\)\\)?)?: (?<time>[0-9:]+(?:\\.[0-9]+)?)");

    static final int ADV_LOG_EXPLOITS_TEXT_INDEX = 1;

    private static class BossPB {
        private final String bossName;
        private final String teamSize;
        private final double time;
    
        public BossPB(String bossName, String teamSize, double time) {
            this.bossName = bossName;
            this.teamSize = teamSize;
            this.time = time;
        }
    
        public String getBossName() {
            return bossName;
        }
    
        public String getTeamSize() {
            return teamSize;
        }
    
        public double getTime() {
            return time;
        }
    }

    @Inject
    public WidgetEvent(DropTrackerPlugin plugin, DropTrackerConfig config,
                            ScheduledExecutorService executor) {
        this.plugin = plugin;
        this.config = config;
        this.executor = executor;
    }

    public void onWidgetLoaded(WidgetLoaded widget)
    {
        switch (widget.getGroupId())
        {
            case InterfaceID.ADVENTURE_LOG:
                advLogLoaded = true;
                break;
            case InterfaceID.KILL_LOG:
                bossLogLoaded = true;
                break;
            case InterfaceID.ACHIEVEMENT_DIARY_SCROLL:
                scrollInterfaceLoaded = true;
                break;
        }
    }
    private void setPb(String boss, double seconds)
    {
        configManager.setRSProfileConfiguration("personalbest", boss.toLowerCase(), seconds);
    }

    @Subscribe
    public void onGameTick(GameTick event)
    {
        if (client.getLocalPlayer() == null)
        {
            return;
        }

        if (advLogLoaded)
        {
            advLogLoaded = false;

            Widget adventureLog = client.getWidget(ComponentID.ADVENTURE_LOG_CONTAINER);

            if (adventureLog != null)
            {
                Matcher advLogExploitsText = ADVENTURE_LOG_TITLE_PATTERN.matcher(adventureLog.getChild(ADV_LOG_EXPLOITS_TEXT_INDEX).getText());
                if (advLogExploitsText.find())
                {
                    pohOwner = advLogExploitsText.group(1);
                }
            }
        }

        if (bossLogLoaded && (pohOwner == null || pohOwner.equals(client.getLocalPlayer().getName())))
        {
            bossLogLoaded = false;

            Widget title = client.getWidget(ComponentID.KILL_LOG_TITLE);
            Widget bossMonster = client.getWidget(ComponentID.KILL_LOG_MONSTER);
            Widget bossKills = client.getWidget(ComponentID.KILL_LOG_KILLS);

            if (title == null || bossMonster == null || bossKills == null
                || !"Boss Kill Log".equals(title.getText()))
            {
                return;
            }

            Widget[] bossChildren = bossMonster.getChildren();
            Widget[] killsChildren = bossKills.getChildren();

            for (int i = 0; i < bossChildren.length; ++i)
            {
                Widget boss = bossChildren[i];
                Widget kill = killsChildren[i];

                String bossName = boss.getText().replace(":", "");
                int kc = Integer.parseInt(kill.getText().replace(",", ""));
                if (kc != getKc(longBossName(bossName)))
                {
                    setKc(longBossName(bossName), kc);
                }
            }
        }

        // Adventure log - Counters
        if (scrollInterfaceLoaded)
        {
            scrollInterfaceLoaded = false;
            
            collectAndSendAdventureLogPBs();
        }
    }



    @VisibleForTesting
    static String secondsToTimeString(double seconds)
    {
        int hours = (int) (Math.floor(seconds) / 3600);
        int minutes = (int) (Math.floor(seconds / 60) % 60);
        seconds = seconds % 60;

        String timeString = hours > 0 ? String.format("%d:%02d:", hours, minutes) : String.format("%d:", minutes);

        // If the seconds is an integer, it is ambiguous if the pb is a precise
        // pb or not. So we always show it without the trailing .00.
        return timeString + (Math.floor(seconds) == seconds ? String.format("%02d", (int) seconds) : String.format("%05.2f", seconds));
    }

    // Method to collect all PBs from the adventure log
    private void collectAndSendAdventureLogPBs() {
        if (client.getLocalPlayer() == null || client.getLocalPlayer().getName() == null || pohOwner == null) {
            return;
        }
        // Only process if we're in our own POH
        if (!client.getLocalPlayer().getName().equals(pohOwner)) {
            return;
        }
        
        List<BossPB> personalBests = new ArrayList<>();
        
        Widget parent = client.getWidget(ComponentID.ACHIEVEMENT_DIARY_SCROLL_TEXT);
        if (parent == null) {
            return;
        }
        
        // Each line is a separate static child
        Widget[] children = parent.getStaticChildren();
        String[] text = Arrays.stream(children)
            .map(Widget::getText)
            .map(Text::removeTags)
            .toArray(String[]::new);
    
        for (int i = 0; i < text.length; ++i) {
            String boss = longBossName(text[i]);
    
            for (i = i + 1; i < text.length; ++i) {
                String line = text[i];
                if (line.isEmpty()) {
                    break;
                }
    
                Matcher matcher = ADVENTURE_LOG_PB_PATTERN.matcher(line);
                if (matcher.find()) {
                    final double seconds = timeStringToSeconds(matcher.group("time"));
                    String teamSize = matcher.group("teamsize");
                    if (teamSize != null) {
                        // 3 player -> 3 players
                        // 1 player -> Solo
                        // Solo -> Solo
                        // 2 players -> 2 players
                        if (teamSize.equals("1 player")) {
                            teamSize = "Solo";
                        } else if (teamSize.endsWith("player")) {
                            teamSize = teamSize.replace("player", "").strip();
                        } else if (teamSize.endsWith("players")) {
                            teamSize = teamSize.replace("players", "").strip();
                        }

                        personalBests.add(new BossPB(boss, teamSize, seconds));
                    } else {
                        personalBests.add(new BossPB(boss, "Solo", seconds));
                    }
                    
                    // Also store in config for future reference
                    setPb(boss + (teamSize != null ? " " + teamSize : ""), seconds);
                }
            }
        }
        
        if (!personalBests.isEmpty()) {
            sendPersonalBestsWebhook(pohOwner, personalBests);
        }
    }
    private void sendPersonalBestsWebhook(String playerName, List<BossPB> personalBests) {
        if (personalBests.isEmpty()) {
            return;
        }
    
        CustomWebhookBody customWebhookBody = new CustomWebhookBody();
        customWebhookBody.setContent(playerName + "'s Personal Best Times:");
    
        // Create a main embed for the PBs
        CustomWebhookBody.Embed pbEmbed = new CustomWebhookBody.Embed();
        pbEmbed.title = playerName + "'s Personal Best Times";
        
        // Add player info fields
        String accountHash = String.valueOf(client.getAccountHash());
        pbEmbed.addField("type", "adventure_log", true);
        pbEmbed.addField("player", playerName, true);
        pbEmbed.addField("acc_hash", accountHash, true);
        
        // Group PBs into batches (5 PBs per field)
        int pbsPerField = 5;
        int fieldCount = (int) Math.ceil(personalBests.size() / (double) pbsPerField);

        List<Integer> playerPets = getPetList();
        
        for (int i = 0; i < fieldCount; i++) {
            StringBuilder fieldContent = new StringBuilder();
            int startIdx = i * pbsPerField;
            int endIdx = Math.min(startIdx + pbsPerField, personalBests.size());
            
            for (int j = startIdx; j < endIdx; j++) {
                BossPB pb = personalBests.get(j);
                String formattedTime = formatTime(pb.getTime());
                
                // Format each PB as with "`boss name` - `team_size` : `time`" for easy parsing
                fieldContent.append("`" + pb.getBossName() + "`")
                           .append(" - ")
                           .append("`" + pb.getTeamSize() + "`")
                           .append(" : ")
                           .append("`" + formattedTime + "`");
                
                // Add a newline between entries (except the last one)
                if (j < endIdx - 1) {
                    fieldContent.append("\n");
                }
            }
            
            // Add the field with a batch number
            pbEmbed.addField("" + (i + 1), fieldContent.toString(), false);
        }
        pbEmbed.addField("Pets", playerPets.toString(), false);
        
        customWebhookBody.getEmbeds().add(pbEmbed);
        
        // Use the main plugin class' sendWebhook method
        plugin.sendDropTrackerWebhook(customWebhookBody, 0);
    }

    private double getPb(String boss)
    {
        Double personalBest = configManager.getRSProfileConfiguration("personalbest", boss.toLowerCase(), double.class);
        return personalBest == null ? 0 : personalBest;
    }

    private void setKc(String boss, int killcount)
    {
        configManager.setRSProfileConfiguration("killcount", boss.toLowerCase(), killcount);
    }

    private int getKc(String boss)
    {
        Integer killCount = configManager.getRSProfileConfiguration("killcount", boss.toLowerCase(), int.class);
        return killCount == null ? 0 : killCount;
    }

    /**
     * Formats a time in seconds to a string in the format mm:ss or h:mm:ss
     */
    private String formatTime(double seconds)
    {
        int hours = (int) (seconds / 3600);
        int minutes = (int) ((seconds % 3600) / 60);
        double secs = seconds % 60;

        if (hours > 0)
        {
            return String.format("%d:%02d:%05.2f", hours, minutes, secs);
        }
        else
        {
            return String.format("%d:%05.2f", minutes, secs);
        }
    }

    private static String longBossName(String boss)
    {
        String lowerBoss = boss.toLowerCase();
        if (lowerBoss.endsWith(" (echo)"))
        {
            String actualBoss = lowerBoss.substring(0, lowerBoss.length() - " (echo)".length());
            return longBossName(actualBoss) + " (Echo)";
        }

        switch (lowerBoss)
        {
            case "corp":
                return "Corporeal Beast";

            case "jad":
            case "tzhaar fight cave":
                return "TzTok-Jad";

            case "kq":
                return "Kalphite Queen";

            case "chaos ele":
                return "Chaos Elemental";

            case "dusk":
            case "dawn":
            case "gargs":
            case "ggs":
            case "gg":
                return "Grotesque Guardians";

            case "crazy arch":
                return "Crazy Archaeologist";

            case "deranged arch":
                return "Deranged Archaeologist";

            case "mole":
                return "Giant Mole";

            case "vetion":
                return "Vet'ion";

            case "calvarion":
            case "calv":
                return "Calvar'ion";

            case "vene":
                return "Venenatis";

            case "kbd":
                return "King Black Dragon";

            case "vork":
                return "Vorkath";

            case "sire":
                return "Abyssal Sire";

            case "smoke devil":
            case "thermy":
                return "Thermonuclear Smoke Devil";

            case "cerb":
                return "Cerberus";

            case "zuk":
            case "inferno":
                return "TzKal-Zuk";

            case "hydra":
                return "Alchemical Hydra";

            // gwd
            case "sara":
            case "saradomin":
            case "zilyana":
            case "zily":
                return "Commander Zilyana";
            case "zammy":
            case "zamorak":
            case "kril":
            case "kril tsutsaroth":
                return "K'ril Tsutsaroth";
            case "arma":
            case "kree":
            case "kreearra":
            case "armadyl":
                return "Kree'arra";
            case "bando":
            case "bandos":
            case "graardor":
                return "General Graardor";

            // dks
            case "supreme":
                return "Dagannoth Supreme";
            case "rex":
                return "Dagannoth Rex";
            case "prime":
                return "Dagannoth Prime";

            case "wt":
                return "Wintertodt";
            case "barrows":
                return "Barrows Chests";
            case "herbi":
                return "Herbiboar";

            // Chambers of Xeric
            case "cox":
            case "xeric":
            case "chambers":
            case "olm":
            case "raids":
                return "Chambers of Xeric";
            case "cox 1":
            case "cox solo":
                return "Chambers of Xeric Solo";
            case "cox 2":
            case "cox duo":
                return "Chambers of Xeric 2 players";
            case "cox 3":
                return "Chambers of Xeric 3 players";
            case "cox 4":
                return "Chambers of Xeric 4 players";
            case "cox 5":
                return "Chambers of Xeric 5 players";
            case "cox 6":
                return "Chambers of Xeric 6 players";
            case "cox 7":
                return "Chambers of Xeric 7 players";
            case "cox 8":
                return "Chambers of Xeric 8 players";
            case "cox 9":
                return "Chambers of Xeric 9 players";
            case "cox 10":
                return "Chambers of Xeric 10 players";
            case "cox 11-15":
            case "cox 11":
            case "cox 12":
            case "cox 13":
            case "cox 14":
            case "cox 15":
                return "Chambers of Xeric 11-15 players";
            case "cox 16-23":
            case "cox 16":
            case "cox 17":
            case "cox 18":
            case "cox 19":
            case "cox 20":
            case "cox 21":
            case "cox 22":
            case "cox 23":
                return "Chambers of Xeric 16-23 players";
            case "cox 24":
            case "cox 24+":
                return "Chambers of Xeric 24+ players";

            // Chambers of Xeric Challenge Mode
            case "chambers of xeric: challenge mode":
            case "cox cm":
            case "xeric cm":
            case "chambers cm":
            case "olm cm":
            case "raids cm":
            case "chambers of xeric - challenge mode":
                return "Chambers of Xeric Challenge Mode";
            case "cox cm 1":
            case "cox cm solo":
                return "Chambers of Xeric Challenge Mode Solo";
            case "cox cm 2":
            case "cox cm duo":
                return "Chambers of Xeric Challenge Mode 2 players";
            case "cox cm 3":
                return "Chambers of Xeric Challenge Mode 3 players";
            case "cox cm 4":
                return "Chambers of Xeric Challenge Mode 4 players";
            case "cox cm 5":
                return "Chambers of Xeric Challenge Mode 5 players";
            case "cox cm 6":
                return "Chambers of Xeric Challenge Mode 6 players";
            case "cox cm 7":
                return "Chambers of Xeric Challenge Mode 7 players";
            case "cox cm 8":
                return "Chambers of Xeric Challenge Mode 8 players";
            case "cox cm 9":
                return "Chambers of Xeric Challenge Mode 9 players";
            case "cox cm 10":
                return "Chambers of Xeric Challenge Mode 10 players";
            case "cox cm 11-15":
            case "cox cm 11":
            case "cox cm 12":
            case "cox cm 13":
            case "cox cm 14":
            case "cox cm 15":
                return "Chambers of Xeric Challenge Mode 11-15 players";
            case "cox cm 16-23":
            case "cox cm 16":
            case "cox cm 17":
            case "cox cm 18":
            case "cox cm 19":
            case "cox cm 20":
            case "cox cm 21":
            case "cox cm 22":
            case "cox cm 23":
                return "Chambers of Xeric Challenge Mode 16-23 players";
            case "cox cm 24":
            case "cox cm 24+":
                return "Chambers of Xeric Challenge Mode 24+ players";

            // Theatre of Blood
            case "tob":
            case "theatre":
            case "verzik":
            case "verzik vitur":
            case "raids 2":
                return "Theatre of Blood";
            case "tob 1":
            case "tob solo":
                return "Theatre of Blood Solo";
            case "tob 2":
            case "tob duo":
                return "Theatre of Blood 2 players";
            case "tob 3":
                return "Theatre of Blood 3 players";
            case "tob 4":
                return "Theatre of Blood 4 players";
            case "tob 5":
                return "Theatre of Blood 5 players";

            // Theatre of Blood Entry Mode
            case "theatre of blood: story mode":
            case "tob sm":
            case "tob story mode":
            case "tob story":
            case "theatre of blood: entry mode":
            case "tob em":
            case "tob entry mode":
            case "tob entry":
                return "Theatre of Blood Entry Mode";

            // Theatre of Blood Hard Mode
            case "theatre of blood: hard mode":
            case "tob cm":
            case "tob hm":
            case "tob hard mode":
            case "tob hard":
            case "hmt":
                return "Theatre of Blood Hard Mode";
            case "hmt 1":
            case "hmt solo":
                return "Theatre of Blood Hard Mode Solo";
            case "hmt 2":
            case "hmt duo":
                return "Theatre of Blood Hard Mode 2 players";
            case "hmt 3":
                return "Theatre of Blood Hard Mode 3 players";
            case "hmt 4":
                return "Theatre of Blood Hard Mode 4 players";
            case "hmt 5":
                return "Theatre of Blood Hard Mode 5 players";

            // Tombs of Amascut
            case "toa":
            case "tombs":
            case "amascut":
            case "warden":
            case "wardens":
            case "raids 3":
                return "Tombs of Amascut";
            case "toa 1":
            case "toa solo":
                return "Tombs of Amascut Solo";
            case "toa 2":
            case "toa duo":
                return "Tombs of Amascut 2 players";
            case "toa 3":
                return "Tombs of Amascut 3 players";
            case "toa 4":
                return "Tombs of Amascut 4 players";
            case "toa 5":
                return "Tombs of Amascut 5 players";
            case "toa 6":
                return "Tombs of Amascut 6 players";
            case "toa 7":
                return "Tombs of Amascut 7 players";
            case "toa 8":
                return "Tombs of Amascut 8 players";
            case "toa entry":
            case "tombs of amascut - entry":
            case "toa entry mode":
                return "Tombs of Amascut Entry Mode";
            case "toa entry 1":
            case "toa entry solo":
                return "Tombs of Amascut Entry Mode Solo";
            case "toa entry 2":
            case "toa entry duo":
                return "Tombs of Amascut Entry Mode 2 players";
            case "toa entry 3":
                return "Tombs of Amascut Entry Mode 3 players";
            case "toa entry 4":
                return "Tombs of Amascut Entry Mode 4 players";
            case "toa entry 5":
                return "Tombs of Amascut Entry Mode 5 players";
            case "toa entry 6":
                return "Tombs of Amascut Entry Mode 6 players";
            case "toa entry 7":
                return "Tombs of Amascut Entry Mode 7 players";
            case "toa entry 8":
                return "Tombs of Amascut Entry Mode 8 players";
            case "tombs of amascut: expert mode":
            case "toa expert":
            case "tombs of amascut - expert":
            case "toa expert mode":
                return "Tombs of Amascut Expert Mode";
            case "toa expert 1":
            case "toa expert solo":
                return "Tombs of Amascut Expert Mode Solo";
            case "toa expert 2":
            case "toa expert duo":
                return "Tombs of Amascut Expert Mode 2 players";
            case "toa expert 3":
                return "Tombs of Amascut Expert Mode 3 players";
            case "toa expert 4":
                return "Tombs of Amascut Expert Mode 4 players";
            case "toa expert 5":
                return "Tombs of Amascut Expert Mode 5 players";
            case "toa expert 6":
                return "Tombs of Amascut Expert Mode 6 players";
            case "toa expert 7":
                return "Tombs of Amascut Expert Mode 7 players";
            case "toa expert 8":
                return "Tombs of Amascut Expert Mode 8 players";

            // The Gauntlet
            case "gaunt":
            case "gauntlet":
            case "the gauntlet":
                return "Gauntlet";

            // Corrupted Gauntlet
            case "cgaunt":
            case "cgauntlet":
            case "the corrupted gauntlet":
            case "cg":
                return "Corrupted Gauntlet";

            // The Nightmare
            case "nm":
            case "tnm":
            case "nmare":
            case "the nightmare":
                return "Nightmare";

            // Phosani's Nightmare
            case "pnm":
            case "phosani":
            case "phosanis":
            case "phosani nm":
            case "phosani nightmare":
            case "phosanis nightmare":
                return "Phosani's Nightmare";

            // Hallowed Sepulchre
            case "hs":
            case "sepulchre":
            case "ghc":
                return "Hallowed Sepulchre";
            case "hs1":
            case "hs 1":
                return "Hallowed Sepulchre Floor 1";
            case "hs2":
            case "hs 2":
                return "Hallowed Sepulchre Floor 2";
            case "hs3":
            case "hs 3":
                return "Hallowed Sepulchre Floor 3";
            case "hs4":
            case "hs 4":
                return "Hallowed Sepulchre Floor 4";
            case "hs5":
            case "hs 5":
                return "Hallowed Sepulchre Floor 5";

            // Colossal Wyrm Basic Agility Course
            case "wbac":
            case "cwbac":
            case "wyrmb":
            case "wyrmbasic":
            case "wyrm basic":
            case "colossal basic":
            case "colossal wyrm basic":
                return "Colossal Wyrm Agility Course (Basic)";

            // Colossal Wyrm Advanced Agility Course
            case "waac":
            case "cwaac":
            case "wyrma":
            case "wyrmadvanced":
            case "wyrm advanced":
            case "colossal advanced":
            case "colossal wyrm advanced":
                return "Colossal Wyrm Agility Course (Advanced)";

            // Prifddinas Agility Course
            case "prif":
            case "prifddinas":
                return "Prifddinas Agility Course";

            // Shayzien Basic Agility Course
            case "shayb":
            case "sbac":
            case "shayzienbasic":
            case "shayzien basic":
                return "Shayzien Basic Agility Course";

            // Shayzien Advanced Agility Course
            case "shaya":
            case "saac":
            case "shayadv":
            case "shayadvanced":
            case "shayzien advanced":
                return "Shayzien Advanced Agility Course";

            // Ape Atoll Agility
            case "aa":
            case "ape atoll":
                return "Ape Atoll Agility";

            // Draynor Village Rooftop Course
            case "draynor":
            case "draynor agility":
                return "Draynor Village Rooftop";

            // Al-Kharid Rooftop Course
            case "al kharid":
            case "al kharid agility":
            case "al-kharid":
            case "al-kharid agility":
            case "alkharid":
            case "alkharid agility":
                return "Al Kharid Rooftop";

            // Varrock Rooftop Course
            case "varrock":
            case "varrock agility":
                return "Varrock Rooftop";

            // Canifis Rooftop Course
            case "canifis":
            case "canifis agility":
                return "Canifis Rooftop";

            // Falador Rooftop Course
            case "fally":
            case "fally agility":
            case "falador":
            case "falador agility":
                return "Falador Rooftop";

            // Seers' Village Rooftop Course
            case "seers":
            case "seers agility":
            case "seers village":
            case "seers village agility":
            case "seers'":
            case "seers' agility":
            case "seers' village":
            case "seers' village agility":
            case "seer's":
            case "seer's agility":
            case "seer's village":
            case "seer's village agility":
                return "Seers' Village Rooftop";

            // Pollnivneach Rooftop Course
            case "pollnivneach":
            case "pollnivneach agility":
                return "Pollnivneach Rooftop";

            // Rellekka Rooftop Course
            case "rellekka":
            case "rellekka agility":
                return "Rellekka Rooftop";

            // Ardougne Rooftop Course
            case "ardy":
            case "ardy agility":
            case "ardy rooftop":
            case "ardougne":
            case "ardougne agility":
                return "Ardougne Rooftop";

            // Agility Pyramid
            case "ap":
            case "pyramid":
                return "Agility Pyramid";

            // Barbarian Outpost
            case "barb":
            case "barb outpost":
                return "Barbarian Outpost";

            // Brimhaven Agility Arena
            case "brimhaven":
            case "brimhaven agility":
                return "Agility Arena";

            // Dorgesh-Kaan Agility Course
            case "dorg":
            case "dorgesh kaan":
            case "dorgesh-kaan":
                return "Dorgesh-Kaan Agility";

            // Gnome Stronghold Agility Course
            case "gnome stronghold":
                return "Gnome Stronghold Agility";

            // Penguin Agility
            case "penguin":
                return "Penguin Agility";

            // Werewolf Agility
            case "werewolf":
                return "Werewolf Agility";

            // Skullball
            case "skullball":
                return "Werewolf Skullball";

            // Wilderness Agility Course
            case "wildy":
            case "wildy agility":
                return "Wilderness Agility";

            // Jad challenge
            case "jad 1":
                return "TzHaar-Ket-Rak's First Challenge";
            case "jad 2":
                return "TzHaar-Ket-Rak's Second Challenge";
            case "jad 3":
                return "TzHaar-Ket-Rak's Third Challenge";
            case "jad 4":
                return "TzHaar-Ket-Rak's Fourth Challenge";
            case "jad 5":
                return "TzHaar-Ket-Rak's Fifth Challenge";
            case "jad 6":
                return "TzHaar-Ket-Rak's Sixth Challenge";

            // Guardians of the Rift
            case "gotr":
            case "runetodt":
            case "rifts closed":
                return "Guardians of the Rift";

            // Tempoross
            case "fishingtodt":
            case "fishtodt":
                return "Tempoross";

            // Phantom Muspah
            case "phantom":
            case "muspah":
            case "pm":
                return "Phantom Muspah";

            // Desert Treasure 2 bosses
            case "the leviathan":
            case "levi":
                return "Leviathan";
            case "duke":
                return "Duke Sucellus";
            case "the whisperer":
            case "whisp":
            case "wisp":
                return "Whisperer";
            case "vard":
                return "Vardorvis";

            // dt2 awakened variants
            case "leviathan awakened":
            case "the leviathan awakened":
            case "levi awakened":
                return "Leviathan (awakened)";
            case "duke sucellus awakened":
            case "duke awakened":
                return "Duke Sucellus (awakened)";
            case "whisperer awakened":
            case "the whisperer awakened":
            case "whisp awakened":
            case "wisp awakened":
                return "Whisperer (awakened)";
            case "vardorvis awakened":
            case "vard awakened":
                return "Vardorvis (awakened)";

            // lunar chest variants
            case "lunar chests":
            case "perilous moons":
            case "perilous moon":
            case "moons of peril":
                return "Lunar Chest";

            // hunter rumour variants
            case "hunterrumour":
            case "hunter contract":
            case "hunter contracts":
            case "hunter tasks":
            case "hunter task":
            case "rumours":
            case "rumour":
                return "Hunter Rumours";

            // sol heredit
            case "sol":
            case "colo":
            case "colosseum":
            case "fortis colosseum":
                return "Sol Heredit";

            case "bird egg":
            case "bird eggs":
            case "bird's egg":
            case "bird's eggs":
                return "Bird's egg offerings";

            case "amox":
                return "Amoxliatl";

            case "the hueycoatl":
            case "huey":
                return "Hueycoatl";

            case "crystal chest":
                return "crystal chest";

            case "larran small chest":
            case "larran's small chest":
                return "Larran's small chest";

            case "larran chest":
            case "larran's chest":
            case "larran big chest":
            case "larran's big chest":
                return "Larran's big chest";

            case "brimstone chest":
                return "Brimstone chest";

            default:
                return WordUtils.capitalize(boss);
        }
    }

    private void loadPets()
    {
        assert petsIconIdx == -1;

        // !pets requires off thread pets access, so we just store a copy
        EnumComposition petsEnum = client.getEnum(EnumID.PETS);
        pets = new int[petsEnum.size()];
        for (int i = 0; i < petsEnum.size(); ++i)
        {
            pets[i] = petsEnum.getIntValue(i);
        }

        final IndexedSprite[] modIcons = client.getModIcons();
        assert modIcons != null;

        final IndexedSprite[] newModIcons = Arrays.copyOf(modIcons, modIcons.length + pets.length);
        petsIconIdx = modIcons.length;

        client.setModIcons(newModIcons);

        for (int i = 0; i < pets.length; i++)
        {
            final int petId = pets[i];

            final AsyncBufferedImage abi = itemManager.getImage(petId);
            final int idx = petsIconIdx + i;
            abi.onLoaded(() ->
            {
                final BufferedImage image = ImageUtil.resizeImage(abi, 18, 16);
                final IndexedSprite sprite = ImageUtil.getImageIndexedSprite(image, client);
                client.getModIcons()[idx] = sprite;
            });
        }
    }

    /**
     * Sets the list of owned pets for the local player
     *
     * @param petList The total list of owned pets for the local player
     */
    private void setPetList(List<Integer> petList)
    {
        if (petList == null)
        {
            return;
        }

        configManager.setRSProfileConfiguration("chatcommands", "pets2",
                gson.toJson(petList));
        configManager.unsetRSProfileConfiguration("chatcommands", "pets"); // old list
    }

    /**
     * Looks up the list of owned pets for the local player
     */
    private List<Pet> getPetListOld()
    {
        String petListJson = configManager.getRSProfileConfiguration("chatcommands", "pets",
                String.class);

        List<Pet> petList;
        try
        {
            // CHECKSTYLE:OFF
            petList = gson.fromJson(petListJson, new TypeToken<List<Pet>>(){}.getType());
            // CHECKSTYLE:ON
        }
        catch (JsonSyntaxException ex)
        {
            return Collections.emptyList();
        }

        return petList != null ? petList : Collections.emptyList();
    }

    private List<Integer> getPetList()
    {
        List<Pet> old = getPetListOld();
        if (!old.isEmpty())
        {
            List<Integer> l = old.stream().map(Pet::getIconID).collect(Collectors.toList());
            setPetList(l);
            return l;
        }

        String petListJson = configManager.getRSProfileConfiguration("chatcommands", "pets2",
                String.class);

        List<Integer> petList;
        try
        {
            // CHECKSTYLE:OFF
            petList = gson.fromJson(petListJson, new TypeToken<List<Integer>>(){}.getType());
            // CHECKSTYLE:ON
        }
        catch (JsonSyntaxException ex)
        {
            return Collections.emptyList();
        }

        return petList != null ? petList : Collections.emptyList();
    }

    @VisibleForTesting
    static double timeStringToSeconds(String timeString)
    {
        String[] s = timeString.split(":");
        if (s.length == 2) // mm:ss
        {
            return Integer.parseInt(s[0]) * 60 + Double.parseDouble(s[1]);
        }
        else if (s.length == 3) // h:mm:ss
        {
            return Integer.parseInt(s[0]) * 60 * 60 + Integer.parseInt(s[1]) * 60 + Double.parseDouble(s[2]);
        }
        return Double.parseDouble(timeString);
    }
    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        switch (event.getGameState())
        {
            case LOADING:
            case HOPPING:
                pohOwner = null;
                break;
            case STARTING:
                petsIconIdx = -1;
                pets = null;
                break;
            case LOGIN_SCREEN:
                if (petsIconIdx == -1)
                {
                    loadPets();
                }
                break;
        }
    }
    
}
