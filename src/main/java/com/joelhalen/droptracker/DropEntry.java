package com.joelhalen.droptracker;

import java.util.concurrent.CompletableFuture;

public class DropEntry {
    private String playerName;
    private String npcOrEventName;
    private int npcCombatLevel;
    private String itemName;
    private int quantity;
    private int geValue;
    private int haValue;
    private int itemId;
    private String clanMembers;
    private int nonMemberCount;
    private String imageLink;


    // getters
    public String getPlayerName() {
        return this.playerName;
    }

    public String getNpcOrEventName() {
        return this.npcOrEventName;
    }

    public int getNpcCombatLevel() {
        return this.npcCombatLevel;
    }
    public String getImageLink() { return this.imageLink; }

    public String getItemName() {
        return this.itemName;
    }
    public int getItemId() {
        return this.itemId;
    }
    public int getQuantity() {
        return this.quantity;
    }

    public int getGeValue() {
        return this.geValue;
    }

    public int getHaValue() {
        return this.haValue;
    }
    public String getClanMembers() {
        return this.clanMembers;
    }
    public int getNonMemberCount() {
        return this.nonMemberCount;
    }

    // setters
    public void setPlayerName(String playerName) {
        this.playerName = playerName;
    }
    public void setImageLink(String imageLink) { this.imageLink = imageLink;
    }

    public void setNpcOrEventName(String npcOrEventName) {
        this.npcOrEventName = npcOrEventName;
    }

    public void setNpcCombatLevel(int npcCombatLevel) {
        this.npcCombatLevel = npcCombatLevel;
    }

    public void setItemName(String itemName) {
        this.itemName = itemName;
    }
    public void setItemId(int itemId) {
        this.itemId = itemId;
    }
    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public void setGeValue(int geValue) {
        this.geValue = geValue;
    }

    public void setHaValue(int haValue) {
        this.haValue = haValue;
    }

    public void setNonMemberCount(int nonMemberCount) {
        this.nonMemberCount = nonMemberCount;
    }
    public void setClanMembers(String clanMembers) {
        this.clanMembers = clanMembers;
    }
}