package io.droptracker.modelexport;

import net.runelite.api.*;

import java.io.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/**
 * Writes a game {@link Model} out as the client holds it, so the player's
 * character - equipment included, since the local player's model is composed
 * from what they are wearing - can be rendered outside the client.
 *
 * <p>Nothing is interpreted here. The server (services/player_model_mesh.py)
 * turns the arrays into a mesh: which faces are drawn, their colours, the
 * texture mapping, and the glTF itself. This only copies them: gzipped,
 * big-endian, every array prefixed with its length (-1 when the model has none).
 */
public final class ModelDump {
    private ModelDump() {
    }

    /** "DTM2": vertices, faces, texture triangles, then each texture the faces name. */
    public static byte[] toBytes(Client client, Model model) throws IOException {
        final int faces = model.getFaceCount();
        final int vertices = model.getVerticesCount();
        if (faces <= 0) {
            throw new IOException("Refusing to write an empty model");
        }
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            out.writeInt(0x44544d32);
            out.writeInt(vertices);
            out.writeInt(faces);
            for (float[] axis : new float[][]{model.getVerticesX(), model.getVerticesY(), model.getVerticesZ()}) {
                for (int i = 0; i < vertices; i++) {
                    out.writeFloat(axis[i]);
                }
            }
            for (int[] perFace : new int[][]{
                model.getFaceIndices1(), model.getFaceIndices2(), model.getFaceIndices3(),
                model.getFaceColors1(), model.getFaceColors2(), model.getFaceColors3()}) {
                ints(out, perFace, faces);
            }
            for (byte[] perFace : new byte[][]{
                model.getFaceTransparencies(), model.getFaceRenderPriorities(), model.getTextureFaces()}) {
                out.writeInt(perFace == null ? -1 : faces);
                if (perFace != null) {
                    out.write(perFace, 0, faces);
                }
            }
            for (int[] corner : new int[][]{
                model.getTexIndices1(), model.getTexIndices2(), model.getTexIndices3()}) {
                ints(out, corner, corner == null ? 0 : corner.length);
            }

            final short[] faceTextures = model.getFaceTextures();
            final Set<Integer> used = new TreeSet<>();
            out.writeInt(faceTextures == null ? -1 : faces);
            if (faceTextures != null) {
                for (int i = 0; i < faces; i++) {
                    out.writeShort(faceTextures[i]);
                    if (faceTextures[i] >= 0) {
                        used.add((int) faceTextures[i]);
                    }
                }
            }

            final TextureProvider provider = client.getTextureProvider();
            final Texture[] definitions = provider.getTextures();
            out.writeInt(used.size());
            for (int id : used) {
                int[] pixels = null;
                try {
                    pixels = provider.load(id);
                } catch (RuntimeException e) {
                    // Fetched lazily, so it may not be there; the server draws it flat.
                }
                final Texture texture = definitions != null && id < definitions.length ? definitions[id] : null;
                out.writeInt(id);
                out.writeInt(texture == null ? 0 : texture.getAnimationDirection());
                out.writeInt(texture == null ? 0 : texture.getAnimationSpeed());
                ints(out, pixels, pixels == null ? 0 : pixels.length);
            }
        }
        return bytes.toByteArray();
    }

    private static void ints(DataOutputStream out, int[] values, int count) throws IOException {
        out.writeInt(values == null ? -1 : count);
        for (int i = 0; values != null && i < count; i++) {
            out.writeInt(values[i]);
        }
    }
}
