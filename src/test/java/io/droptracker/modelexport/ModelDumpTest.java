package io.droptracker.modelexport;

import io.droptracker.testing.RuneLiteStubs;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.Texture;
import net.runelite.api.TextureProvider;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.junit.Assert.assertEquals;

/**
 * The layout the server reads (disc services/player_model_mesh.py, parse_raw).
 * That the server turns it into the right mesh is pinned on its side, against
 * dumps this class wrote.
 */
public class ModelDumpTest {
    private static Model model(int faces, short[] faceTextures) {
        Model model = RuneLiteStubs.stub(Model.class, "model");
        Map<String, Object> state = RuneLiteStubs.state(model);
        state.put("getFaceCount", faces);
        state.put("getVerticesCount", 3);
        // Longer than the counts, as the client's arrays are.
        state.put("getVerticesX", new float[]{1f, 2f, 3f, 99f});
        state.put("getVerticesY", new float[]{4f, 5f, 6f, 99f});
        state.put("getVerticesZ", new float[]{7f, 8f, 9f, 99f});
        state.put("getFaceIndices1", new int[]{0, 99});
        state.put("getFaceIndices2", new int[]{1, 99});
        state.put("getFaceIndices3", new int[]{2, 99});
        state.put("getFaceColors1", new int[]{100, 99});
        state.put("getFaceColors2", new int[]{200, 99});
        state.put("getFaceColors3", new int[]{-1, 99});
        state.put("getFaceTransparencies", new byte[]{(byte) 200, 9});
        state.put("getFaceRenderPriorities", null);
        state.put("getTextureFaces", null);
        state.put("getTexIndices1", null);
        state.put("getTexIndices2", null);
        state.put("getTexIndices3", null);
        state.put("getFaceTextures", faceTextures);
        return model;
    }

    private static Client client() {
        Texture texture = RuneLiteStubs.stub(Texture.class, "texture");
        RuneLiteStubs.state(texture).put("getAnimationDirection", 3);
        RuneLiteStubs.state(texture).put("getAnimationSpeed", 2);
        TextureProvider provider = RuneLiteStubs.stub(TextureProvider.class, "textures");
        RuneLiteStubs.state(provider).put("getTextures", new Texture[]{null, texture});
        RuneLiteStubs.state(provider).put("load", (RuneLiteStubs.Answer) args -> new int[]{11, 0, 13, 14});
        Client client = RuneLiteStubs.stub(Client.class, "client");
        RuneLiteStubs.state(client).put("getTextureProvider", provider);
        return client;
    }

    private static DataInputStream read(byte[] dump) throws IOException {
        return new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(dump)));
    }

    @Test
    public void writesTheModelsArraysTrimmedToItsCounts() throws IOException {
        DataInputStream in = read(ModelDump.toBytes(client(), model(1, new short[]{1, 99})));
        assertEquals(0x44544d32, in.readInt());
        assertEquals(3, in.readInt());
        assertEquals(1, in.readInt());
        for (float expected = 1f; expected <= 9f; expected++) {
            assertEquals(expected, in.readFloat(), 0f);
        }
        for (int expected : new int[]{0, 1, 2, 100, 200, -1}) {
            assertEquals(1, in.readInt());
            assertEquals(expected, in.readInt());
        }
        assertEquals(1, in.readInt());
        assertEquals((byte) 200, in.readByte());
        // No priorities, no texture faces, no texture triangles.
        for (int i = 0; i < 5; i++) {
            assertEquals(-1, in.readInt());
        }
        assertEquals(1, in.readInt());
        assertEquals(1, in.readShort());
        // One texture: id, direction, speed, pixels.
        for (int expected : new int[]{1, 1, 3, 2, 4, 11, 0, 13, 14}) {
            assertEquals(expected, in.readInt());
        }
        assertEquals(-1, in.read());
    }

    @Test
    public void anUntexturedModelSendsNoTextures() throws IOException {
        DataInputStream in = read(ModelDump.toBytes(client(), model(1, null)));
        in.skipBytes(12 + 36 + 48 + 5 + 20);
        assertEquals(-1, in.readInt());
        assertEquals(0, in.readInt());
        assertEquals(-1, in.read());
    }

    @Test(expected = IOException.class)
    public void refusesAModelWithNoFaces() throws IOException {
        ModelDump.toBytes(client(), model(0, null));
    }
}
