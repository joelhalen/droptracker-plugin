package io.droptracker.util;

import javax.annotation.Nullable;

/**
 * Decides which RSN a submission is sent under.
 *
 * <p>Every submission the plugin builds carries a {@code player_name}, and the
 * backend resolves that name against a real account. An unreadable RSN used to
 * be replaced with the literal string {@code "Unknown"} — which is not an inert
 * placeholder. It is a name like any other, it matches a real (but empty) Wise
 * Old Man record, and adopting that record renamed whichever live account the
 * submission resolved to, breaking its identity and silently discarding
 * everything it submitted afterwards. The server no longer trusts that record,
 * but the plugin has no business inventing a name in the first place.
 *
 * <p>So: resolve honestly or not at all. A caller that gets {@code null} skips
 * the submission — an event with no identity has nothing to attach itself to.
 */
public final class PlayerIdentity {

    private PlayerIdentity() {
    }

    /**
     * Resolves the RSN to submit under.
     *
     * <p>The live name is unreadable whenever the local player is gone: at
     * logout and during a world hop (which is where most of these come from —
     * the XP handler snapshots on both), and in the short window after login
     * before the name arrives. The account hash outlives the local player in
     * all three, so the cached name covers them — and it is trusted only when
     * it was stored against the hash logged in right now, so it can never
     * attribute an event to whichever account the player was on previously.
     *
     * @param liveName          name read from the client
     * @param accountHash       account hash of the session being submitted for
     * @param cachedName        last name stored by the plugin at login
     * @param cachedAccountHash account hash {@code cachedName} was stored for
     * @return the RSN, or {@code null} when it cannot be established
     */
    @Nullable
    public static String resolve(@Nullable String liveName, @Nullable String accountHash,
                                 @Nullable String cachedName, @Nullable String cachedAccountHash) {
        if (liveName != null && !liveName.trim().isEmpty()) {
            return liveName;
        }
        if (cachedName == null || cachedName.trim().isEmpty()) {
            return null;
        }
        // The account hash is the only thing that proves the cached name
        // belongs to this session rather than the last one.
        if (!isUsableAccountHash(accountHash) || cachedAccountHash == null
                || !accountHash.trim().equals(cachedAccountHash.trim())) {
            return null;
        }
        return cachedName;
    }

    /**
     * True when an account hash identifies a logged-in account. The client
     * reports -1 when logged out, and the plugin substitutes 0 when there is no
     * client at all; both are strings the backend would otherwise try to match
     * a player row against.
     */
    public static boolean isUsableAccountHash(@Nullable String accountHash) {
        if (accountHash == null) {
            return false;
        }
        String trimmed = accountHash.trim();
        return !trimmed.isEmpty() && !"0".equals(trimmed) && !"-1".equals(trimmed);
    }

    /**
     * True when a submission's {@code player_name} field is unusable. Used as
     * the last line of defence before a payload leaves the client.
     */
    public static boolean isMissingName(@Nullable String playerName) {
        return playerName == null || playerName.trim().isEmpty();
    }
}
