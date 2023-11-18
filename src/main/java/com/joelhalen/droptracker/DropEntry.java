/*      BSD 2-Clause License

		Copyright (c) 2023, joelhalen

		Redistribution and use in source and binary forms, with or without
		modification, are permitted provided that the following conditions are met:

		1. Redistributions of source code must retain the above copyright notice, this
		list of conditions and the following disclaimer.

		2. Redistributions in binary form must reproduce the above copyright notice,
		this list of conditions and the following disclaimer in the documentation
		and/or other materials provided with the distribution.

		THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
		AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
		IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
		DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
		FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
		DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
		SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
		CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
		OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
		OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.     */
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