/*
 * Adapted from RuneProfile (github.com/ReinhardtR/runeprofile-plugin),
 * Copyright (c) 2022, Reinhardt Rijna, BSD 2-Clause License (see LICENSE). Exports a game Model as binary glTF so the player's
 * character - equipment included, since the local player's model is composed
 * from what they are wearing - can be rendered outside the client.
 */
package io.droptracker.modelexport;

import lombok.NonNull;
import net.runelite.api.*;

import java.io.*;
import java.util.*;

/**
 * Exports a game {@link Model} as a mesh dump the server turns into a binary
 * glTF (services/player_model_mesh.py). Writing the glTF itself is pure
 * serialisation, so it happens there; this sends exactly what it needs.
 */
public final class GlbExporter {
    private GlbExporter() {
    }

    /** "DTM1": the mesh, then each primitive with its texture (sent once per texture). */
    public static byte[] toBytes(@NonNull Client client, @NonNull Model model,
                                 @NonNull String name) throws IOException {
        return write(ModelMeshBuilder.build(model), new GameTextures(client)::get, name, model.getFaceCount());
    }

    static byte[] write(MeshData mesh, java.util.function.IntFunction<GameTextures.TextureData> textures,
                        String name, int faceCount) throws IOException {
        if (mesh.isEmpty()) {
            throw new IOException("Refusing to write an empty model");
        }
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(0x44544d31);
        out.writeUTF(name);
        out.writeInt(faceCount);
        floats(out, mesh.getPositions());
        out.writeInt(mesh.getColors().length);
        out.write(mesh.getColors());
        floats(out, mesh.getUvs());
        floats(out, mesh.getPriorities());
        out.writeInt(mesh.getIndices().length);
        for (int index : mesh.getIndices()) {
            out.writeInt(index);
        }
        floats(out, mesh.getMin());
        floats(out, mesh.getMax());
        out.writeInt(mesh.getPrimitives().size());
        final Set<Integer> sent = new HashSet<>();
        for (MeshData.Primitive p : mesh.getPrimitives()) {
            out.writeInt(p.getTextureId());
            out.writeInt(p.getIndexOffset());
            out.writeInt(p.getIndexCount());
            out.writeBoolean(p.isTranslucent());
            final GameTextures.TextureData t = p.isTextured() ? textures.apply(p.getTextureId()) : null;
            // 0: no texture; 1: its data follows; 2: sent with an earlier primitive
            final int state = t == null ? 0 : sent.add(t.getId()) ? 1 : 2;
            out.writeByte(state);
            if (state == 1) {
                out.writeInt(t.getAverageColor());
                out.writeFloat(t.getScrollU());
                out.writeFloat(t.getScrollV());
                out.writeInt(t.getPng() == null ? -1 : t.getPng().length);
                if (t.getPng() != null) {
                    out.write(t.getPng());
                }
            }
        }
        return bytes.toByteArray();
    }

    private static void floats(DataOutputStream out, float[] values) throws IOException {
        out.writeInt(values == null ? -1 : values.length);
        if (values != null) {
            for (float v : values) {
                out.writeFloat(v);
            }
        }
    }
}
