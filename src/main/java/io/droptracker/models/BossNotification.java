package io.droptracker.models;
/*  Original Author @pajlads - DinkPlugin

    https://github.com/pajlads/DinkPlugin

 */

import com.google.gson.annotations.JsonAdapter;
import io.droptracker.util.DurationAdapter;
import lombok.EqualsAndHashCode;
import lombok.Value;
import lombok.experimental.Accessors;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

@Value
@EqualsAndHashCode(callSuper = false)
public class BossNotification {
    public List<CustomWebhookBody.Embed> getFields() {
        return Collections.emptyList();
    }
    String boss;
    Integer count;
    String gameMessage;
    @JsonAdapter(DurationAdapter.class)
    Duration time;
    @Accessors(fluent = true)
    Boolean isPersonalBest;
}
