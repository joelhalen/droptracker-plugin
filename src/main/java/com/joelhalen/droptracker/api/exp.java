package com.joelhalen.droptracker.api;

import com.joelhalen.droptracker.DropTrackerPluginConfig;

import javax.inject.Inject;

public class exp
{
    private DropTrackerPluginConfig config;
    private String skill;
    private Integer amount;
    private Integer currentTotal;
    private String apiKey;
    private Long accountHash;


    public exp(String skill, Integer amount, Integer currentTotal, String apiKey, Long accountHash, DropTrackerPluginConfig config) {
        this.skill = skill;
        this.amount = amount;
        this.currentTotal = currentTotal;
        this.apiKey = apiKey;
        this.accountHash = accountHash;
        this.config = config;
    }

    // Getter methods
    public String getSkill() {
        return skill;
    }

    public Integer getAmount() {
        return amount;
    }

    public Integer getCurrentTotal() {
        return currentTotal;
    }

    public String getApiKey() {
        return config.authKey();
    }

    public Long getAccountHash() {
        return accountHash;
    }

    // Setter methods
    public void setSkill(String skill) {
        this.skill = skill;
    }

    public void setAmount(Integer amount) {
        this.amount = amount;
    }

    public void setCurrentTotal(Integer currentTotal) {
        this.currentTotal = currentTotal;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public void setAccountHash(Long accountHash) {
        this.accountHash = accountHash;
    }
}
