package io.droptracker.util;


import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Setter;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ItemComposition;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemVariationMapping;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.stream.Collectors;

@Slf4j
@Singleton
public class Rarity {
    private final Map<String, Collection<Drop>> dropsByNpcName = new HashMap<>(1024);
    private @Inject Gson gson;
    private @Inject ItemManager itemManager;
    public static final double EPSILON = 0.00001;
    private static final int[] FACTORIALS;

    @Inject
    void init() {
        Map<String, List<RawDrop>> raw;
        try (InputStream is = getClass().getResourceAsStream("/npc_drops.json");
             Reader reader = new BufferedReader(new InputStreamReader(Objects.requireNonNull(is)))) {
            raw = gson.fromJson(reader,
                    new TypeToken<Map<String, List<RawDrop>>>() {}.getType());
        } catch (Exception e) {
            log.error("Failed to read monster drop rates", e);
            return;
        }

        raw.forEach((npcName, rawDrops) -> {
            List<Drop> drops = rawDrops.stream()
                    .map(RawDrop::transform)
                    .flatMap(Collection::stream)
                    .collect(Collectors.toList());
            dropsByNpcName.put(npcName, drops);
        });
    }

    public OptionalDouble getRarity(String npcName, int itemId, int quantity) {
        ItemComposition composition = itemId >= 0 ? itemManager.getItemComposition(itemId) : null;
        int canonical = composition != null && composition.getNote() != -1 ? composition.getLinkedNoteId() : itemId;
        String itemName = composition != null ? composition.getMembersName() : "";
        Collection<Integer> variants = new HashSet<>(
                ItemVariationMapping.getVariations(ItemVariationMapping.map(canonical))
        );
        return dropsByNpcName.getOrDefault(npcName, Collections.emptyList())
                .stream()
                .filter(drop -> drop.getMinQuantity() <= quantity && quantity <= drop.getMaxQuantity())
                .filter(drop -> {
                    int id = drop.getItemId();
                    if (id == itemId) return true;
                    return variants.contains(id) && itemName.equals(itemManager.getItemComposition(id).getMembersName());
                })
                .mapToDouble(Drop::getProbability)
                .reduce(Double::sum);
    }

    @Value
    private static class Drop {
        int itemId;
        int minQuantity;
        int maxQuantity;
        double probability;
    }

    @Data
    @Setter(AccessLevel.PRIVATE)
    private static class RawDrop {
        private @SerializedName("i") int itemId;
        private @SerializedName("r") Integer rolls;
        private @SerializedName("d") double denominator;
        private @SerializedName("q") Integer quantity;
        private @SerializedName("m") Integer quantMin;
        private @SerializedName("n") Integer quantMax;

        Collection<Drop> transform() {
            int rounds = rolls != null ? rolls : 1;
            int min = quantMin != null ? quantMin : quantity;
            int max = quantMax != null ? quantMax : quantity;
            double prob = 1 / denominator;

            if (rounds == 1) {
                return List.of(new Drop(itemId, min, max, prob));
            }
            List<Drop> drops = new ArrayList<>(rounds);
            for (int successCount = 1; successCount <= rounds; successCount++) {
                double density = binomialProbability(prob, rounds, successCount);
                drops.add(new Drop(itemId, min * successCount, max * successCount, density));
            }
            return drops;
        }
        public double binomialProbability(double p, int nTrials, int kSuccess) {
            // https://en.wikipedia.org/wiki/Binomial_distribution#Probability_mass_function
            return binomialCoefficient(nTrials, kSuccess) * Math.pow(p, kSuccess) * Math.pow(1 - p, nTrials - kSuccess);
        }
        private int binomialCoefficient(int n, int k) {
            assert n < FACTORIALS.length && k <= n && k >= 0;
            return FACTORIALS[n] / (FACTORIALS[k] * FACTORIALS[n - k]); // https://en.wikipedia.org/wiki/nCk
        }
    }

    static {
        // precompute factorials from 0 to 9 for n-choose-k formula
        int n = 10; // max rolls in npc_drops.json is 9 (for Bloodthirsty Leagues IV tier 5 relic)
        int[] facts = new int[n];
        facts[0] = 1; // 0! = 1
        for (int i = 1; i < n; i++) {
            facts[i] = i * facts[i - 1];
        }
        FACTORIALS = facts;
    }
}