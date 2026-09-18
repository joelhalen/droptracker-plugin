package io.droptracker.models.api;

import com.google.common.annotations.VisibleForTesting;
import com.google.gson.annotations.SerializedName;

/**
 * Response of POST /player/model/check: what the server already holds for one
 * outfit fingerprint.
 *
 * <p>Asked before exporting, because exporting is the expensive half. A GLB is
 * ~53 KB at the median and the export runs on the client thread, while this
 * answer costs a couple of hundred bytes — and the server measured four in five
 * uploads as an outfit it already had, thrown away on arrival.
 *
 * <p>{@code accepted} false means the server does not know this account yet
 * (identity comes from submissions), so sending the model would be refused the
 * same way. Everything else is a reason to send it.
 */
public class ModelStatus {

    @SerializedName("accepted")
    private boolean accepted;

    @SerializedName("has_model")
    private boolean hasModel;

    /**
     * Whether the stored outfit also has the player's pet with it. An outfit
     * stored while no pet was out has none, and the fingerprint cannot say so:
     * it covers the character's own appearance, not what is following them.
     */
    @SerializedName("has_pet")
    private boolean hasPet;

    /** Written by Gson in production; this is for the tests. */
    @VisibleForTesting
    public ModelStatus(boolean accepted, boolean hasModel, boolean hasPet) {
        this.accepted = accepted;
        this.hasModel = hasModel;
        this.hasPet = hasPet;
    }

    public ModelStatus() {
    }

    // Hand-written rather than Lombok's: @Getter would name these isHasModel().
    public boolean isAccepted() {
        return accepted;
    }

    public boolean hasModel() {
        return hasModel;
    }

    public boolean hasPet() {
        return hasPet;
    }
}
