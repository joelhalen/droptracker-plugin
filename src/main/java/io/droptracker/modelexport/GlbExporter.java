/*
 * Adapted from RuneProfile (github.com/ReinhardtR/runeprofile-plugin),
 * Copyright (c) 2022, Reinhardt Rijna, BSD 2-Clause License (see LICENSE). Exports a game Model as binary glTF so the player's
 * character - equipment included, since the local player's model is composed
 * from what they are wearing - can be rendered outside the client.
 */
package io.droptracker.modelexport;

import lombok.NonNull;
import net.runelite.api.Client;
import net.runelite.api.Model;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Exports a game {@link Model} as a binary glTF.
 */
public final class GlbExporter {
    private GlbExporter() {
    }

    /**
     * Writes a self contained GLB with every texture embedded. One file, opens
     * in any glTF viewer, no external requests.
     */
    public static byte[] toBytes(@NonNull Client client, @NonNull Model model,
                                 @NonNull String name) throws IOException {
        return toBytes(client, model, new GlbWriter.Options().embedTextures().modelName(name));
    }

    public static byte[] toBytes(@NonNull Client client, @NonNull Model model,
                                 @NonNull GlbWriter.Options options) throws IOException {
        final MeshData mesh = ModelMeshBuilder.build(model);
        final GameTextures textures = new GameTextures(client);

        final Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("source", "droptracker-plugin");
        extras.put("faceCount", model.getFaceCount());
        extras.put("vertexCount", mesh.getVertexCount());
        options.extras(extras);

        return GlbWriter.write(mesh, textures::get, options);
    }
}
