package io.droptracker.util;

import net.runelite.client.util.Text;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ChatTagVariablesTest {

    /**
     * The live clan quest broadcast since rev241 (2026-10-07). Before the
     * expansion, removeTags left "no pk pleae has completed a quest:" and the
     * Discord bridge mirrored it with no quest name.
     */
    @Test
    public void questBroadcastKeepsItsName() {
        String raw = "<img=2><str_quest_name_0=Big Chompy Bird Hunting>no pk pleae has completed a quest: "
            + "<col=0c8f04><str_quest_name_0></col>";
        assertEquals("no pk pleae has completed a quest: Big Chompy Bird Hunting",
            Text.removeTags(ChatMessageUtil.expandTagVariables(raw)).trim());
    }

    @Test
    public void ordinaryMarkupIsUntouched() {
        String raw = "<col=ff0000>Dragon pickaxe</col> <img=41><str>gone</str>";
        assertEquals(raw, ChatMessageUtil.expandTagVariables(raw));
        assertEquals("plain line", ChatMessageUtil.expandTagVariables("plain line"));
    }

    @Test
    public void undefinedReferenceIsLeftForRemoveTags() {
        String raw = "<str_a_0=x>value <str_b_0>";
        assertEquals("value <str_b_0>", ChatMessageUtil.expandTagVariables(raw));
    }

    @Test
    public void valueWithReplacementCharactersIsLiteral() {
        assertEquals("cost $1 \\ ok", ChatMessageUtil.expandTagVariables("<str_v_0=$1 \\ ok>cost <str_v_0>"));
    }
}
