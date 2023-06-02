package com.joelhalen.droptracker;

import java.util.concurrent.CompletableFuture;

public class DropEntryStream {
    private String playerName;
    private String npcOrEventName;
    private String itemName;
    private int quantity;
    private int geValue;
    private int itemId;


    // getters
    public String getPlayerName() {
        return this.playerName;
    }

    public String getNpcOrEventName() {
        return this.npcOrEventName;
    }

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


    // setters
    public void setPlayerName(String playerName) {
        this.playerName = playerName;
    }

    public void setNpcOrEventName(String npcOrEventName) {
        this.npcOrEventName = npcOrEventName;
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


}